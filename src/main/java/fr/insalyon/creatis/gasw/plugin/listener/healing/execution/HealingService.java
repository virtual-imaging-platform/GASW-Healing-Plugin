package fr.insalyon.creatis.gasw.plugin.listener.healing.execution;

import fr.insalyon.creatis.gasw.*;
import fr.insalyon.creatis.gasw.bean.Job;
import fr.insalyon.creatis.gasw.bean.JobMinorStatus;
import fr.insalyon.creatis.gasw.dao.DAOException;
import fr.insalyon.creatis.gasw.dao.JobDAO;
import fr.insalyon.creatis.gasw.dao.JobMinorStatusDAO;
import fr.insalyon.creatis.gasw.execution.GaswMinorStatus;
import fr.insalyon.creatis.gasw.execution.GaswStatus;
import fr.insalyon.creatis.gasw.plugin.listener.healing.HealingConfiguration;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandState.Timings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class HealingService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final GaswConfiguration gaswConfiguration;
    private final HealingConfiguration healingConfiguration;
    private final GaswNotification gaswNotification;
    private final JobDAO jobDAO;
    private final JobMinorStatusDAO jobMinorStatusDAO;

    public HealingService(
            GaswConfiguration gaswConfiguration,
            HealingConfiguration healingConfiguration,
            GaswNotification gaswNotification,
            JobDAO jobDAO,
            JobMinorStatusDAO jobMinorStatusDAO) {
        this.gaswConfiguration = gaswConfiguration;
        this.healingConfiguration = healingConfiguration;
        this.gaswNotification = gaswNotification;
        this.jobDAO = jobDAO;
        this.jobMinorStatusDAO = jobMinorStatusDAO;
    }

    public void computeAndUpdateErrorRates(CommandState state) throws DAOException {
        String command = state.getCommand();
        try {
            int total = jobDAO.getJobsByCommand(command).size();
            int failed = jobDAO.getFailedByCommand(command).size();
            double jobErrorRate = total == 0 ? 0.0 : 100.0 * failed / total;
            List<Integer> invocations = jobDAO.getInvocationsByCommand(command);
            // TODO : after further analysis, also consider jobs running for more than MAX hours when computing invocationPartialErrorRate
            long failures = invocations.stream()
                    .filter(this::hasFailedJobs)
                    .count();
            double invocationPartialErrorRate = invocations.isEmpty() ? 0.0 : 100.0 * failures / invocations.size();

            state.updateErrorRates(jobErrorRate, invocationPartialErrorRate);
            logger.info("Error rates for command [{}] — job error rate : {}%, invocation partial error rate : {}%",
                    command, getFormattedNumber(jobErrorRate, 2), getFormattedNumber(invocationPartialErrorRate, 2));
            if (invocations.size() >= healingConfiguration.getMinInvocations()) {
                boolean breached = jobErrorRate >= healingConfiguration.getMaxErrorJobPercentage() ||
                        invocationPartialErrorRate >= healingConfiguration.getMaxErrorInvocationPercentage();
                if (breached && !state.shouldKillAll()) {
                    state.markKillAll();
                    logger.info("Attention, updating killing decision to true. Nb min invocations are {} , job error rate is {} and invocation error rate is {}",
                            healingConfiguration.getMinInvocations(), getFormattedNumber(jobErrorRate, 2), getFormattedNumber(invocationPartialErrorRate, 2));
                }
            }
        } catch (DAOException ex) {
            logger.error("Error computing error rates for command [{}]: ", command, ex);
        }
    }

    public void replicateJobs(CommandState state) {
        try {
            Timings medians = state.computeMedians();
            logTimingsIfNecessary(state, medians);

            for (Job runningJob : jobDAO.getRunningByCommand(state.getCommand())) {
                List<Job> activeJobs = jobDAO.getActiveJobsByInvocationID(runningJob.getInvocationID());
                List<Job> failedJobs = jobDAO.getFailedJobsByInvocationID(runningJob.getInvocationID());
                // Only heal if all the active jobs are RUNNING and if
                // none is an temporary state
                if (canDoHealingForJobs(activeJobs, failedJobs)) {
                    // if OK, do the healing on the running jobs
                    doHealing(activeJobs, failedJobs, medians);
                }
            }
        } catch (DAOException ex) {
            logger.error("Error replicating or finding jobs for command [{}]: ", state.getCommand(), ex);
        }
    }

    public void killAllJobs(CommandState commandState) {
        logger.info("Killing all jobs for command [{}]", commandState.getCommand());
        try {
            for (int invocationId : jobDAO.getInvocationsByCommand(commandState.getCommand())) {
                killInvocationJobs(invocationId);
            }

            if (jobDAO.getActiveJobsByCommand(commandState.getCommand()).isEmpty()) {
                //This is needed for certain Moteur workflows (e.g., GATE) for which the workflow is not completed when there are no jobs left
                //TODO: remove this when the completion issue is fixed on the workflow side
                logger.info("No active jobs remain for [{}] — healing complete.", commandState.getCommand());
                commandState.markAllJobsEnded();
            }
        } catch (DAOException ex) {
            logger.error("Error killing jobs for command [{}]: ", commandState.getCommand(), ex);
        }
    }

    private boolean canDoHealingForJobs(List<Job> activeJobs, List<Job> failedJobs) throws DAOException {
        // do NOT do healing when
        // - a job is in a temporary state
        //      (replicating, restarting, finishing, being killed)
        // - a job is active but not running (submitted, queued)

        // first check on the jobs internal information to avoid database access
        for (Job job : activeJobs) {
            if (job.isReplicating() || job.getStatus() != GaswStatus.RUNNING) return false;
        }

        for (Job job : failedJobs) {
            if (job.isReplicating()) return false;
        }
        // to do database access only when necessary, check for minor statuses
        // only after checking all jobs internal information
        for (Job job : activeJobs) {
            if (hasFinished(job)) return false;
        }

        return true;
    }

    private void doHealing(List<Job> activeJobs, List<Job> failedJobs, Timings medians) {
        try {
            JobPhases bestJob = selectBestJob(activeJobs, medians);
            if (bestJob == null) return;

            boolean belowMaxReplicas = activeJobs.size() < healingConfiguration.getMaxReplicas();
            boolean belowRetryLimit = (failedJobs.size() - 1) < gaswConfiguration.getDefaultRetryCount();
            boolean isBlocked = (double) bestJob.estimation() / medians.total() >= healingConfiguration.getBlockedCoefficient();
            if (belowMaxReplicas && belowRetryLimit && isBlocked) {
                Job job = bestJob.job();
                logger.info("Replicating: {} (jobEstimation: {}) ", job.getId(), bestJob.estimation());
                job.setStatus(GaswStatus.REPLICATE);
                jobDAO.update(job);
            }
        } catch (DAOException ex) {
            logger.error("Error during healing decision: ", ex);
        }
    }

    private JobPhases selectBestJob(List<Job> jobs, Timings medians) throws DAOException {
        JobPhases bestJob = null;
        try {
            for (Job job : jobs) {
                JobPhases newJob = evaluateJobPhases(job, medians);
                if (bestJob == null) {
                    bestJob = newJob;
                    continue;
                }

                if (newJob.estimation() < bestJob.estimation()) {
                    killReplicaIfNecessary(bestJob, newJob);
                    bestJob = newJob;
                    continue;
                }

                killReplicaIfNecessary(newJob, bestJob);
            }
        } catch (GaswException | DAOException ex) {
            logger.error("Error looking for jobs to replicate: ", ex);
        }

        return bestJob;
    }

    private JobPhases evaluateJobPhases(Job job, Timings medians) throws GaswException, DAOException {
        long startTime = 0;
        long setupTime = 0;
        long inputTime = 0;
        long executionTime = 0;
        long uploadTime = 0;
        long estimation = 0;
        GaswMinorStatus lastStatus = null;

        for (JobMinorStatus status : jobMinorStatusDAO.getExecutionMinorStatus(job.getId())) {
            switch (status.getStatus()) {
                case Started:
                    startTime = status.getDate().getTime();
                    lastStatus = GaswMinorStatus.Started;
                    break;
                case Inputs:
                    setupTime = status.getDate().getTime() - startTime;
                    lastStatus = GaswMinorStatus.Inputs;
                    estimation += setupTime;
                    break;
                case Application:
                    inputTime = status.getDate().getTime() - setupTime - startTime;
                    lastStatus = GaswMinorStatus.Application;
                    estimation += inputTime;
                    break;
                case Outputs:
                    executionTime = status.getDate().getTime() - inputTime - setupTime - startTime;
                    lastStatus = GaswMinorStatus.Outputs;
                    estimation += executionTime;
                    break;
                case Finished:
                    uploadTime = status.getDate().getTime() - executionTime - inputTime - setupTime - startTime;
                    lastStatus = GaswMinorStatus.Finished;
                    estimation += uploadTime;
            }
        }

        long currentTime = new Date().getTime();
        if (lastStatus != null) {
            switch (lastStatus) {
                case Started:
                    setupTime = currentTime - startTime;
                    estimation = Math.max(setupTime, medians.setup()) + medians.input() + medians.execution() + medians.output();
                    break;
                case Inputs:
                    inputTime = currentTime - setupTime - startTime;
                    estimation += Math.max(inputTime, medians.input()) + medians.execution() + medians.output();
                    break;
                case Application:
                    executionTime = currentTime - startTime - setupTime - inputTime;
                    estimation += Math.max(executionTime, medians.execution()) + medians.output();
                    break;
                case Outputs:
                    uploadTime = currentTime - startTime - setupTime - inputTime - executionTime;
                    estimation += Math.max(uploadTime, medians.output());
            }
        } else {
            estimation = medians.total();
        }

        return new JobPhases(job, estimation, lastStatus);
    }

    private void killReplicaIfNecessary(JobPhases jobToEvaluatePhase, JobPhases bestJobPhase) throws DAOException {
        // Do nothing if the job is not in an equal or more advanced state
        if (jobToEvaluatePhase.getLastStatusCode() >= bestJobPhase.getLastStatusCode()) return;

        boolean slowReplica = (double) jobToEvaluatePhase.estimation() / bestJobPhase.estimation()
                >= healingConfiguration.getBlockedCoefficient();
        if (!slowReplica) return;

        Job jobToKill = jobToEvaluatePhase.job();
        Job bestJob = bestJobPhase.job();
        logger.info("Killing replica: {} because {} is better", jobToKill.getId(), bestJob.getId());
        logger.info("Status: {} vs {}", jobToEvaluatePhase.getLastStatusCode(), bestJobPhase.getLastStatusCode());
        logger.info("Estimations: {} vs {}", jobToEvaluatePhase.estimation(), bestJobPhase.estimation());
        jobToKill.setStatus(GaswStatus.KILL_REPLICA);
        jobDAO.update(jobToKill);
    }

    private void killInvocationJobs(int invocationId) {
        try {
            List<Job> activeJobs = jobDAO.getActiveJobsByInvocationID(invocationId);
            if (activeJobs != null && !activeJobs.isEmpty()) {
                logger.info("Killing jobs for invocation {}", invocationId);
                GaswStatus status = GaswStatus.KILL;
                for (Job job : activeJobs) {
                    job.setStatus(status);
                    job.setBeingKilled(true);
                    jobDAO.update(job);
                    logger.info("Setting status of job {} to {}", job.getId(), status);
                    // All subsequent jobs are replica, so kill them as such
                    status = GaswStatus.KILL_REPLICA;
                }
            } else if (jobDAO.getNumberOfCompletedJobsByInvocationID(invocationId) == 0) {
                handleHeldJobs(invocationId);
            }
        } catch (DAOException ex) {
            logger.error("Error killing jobs for invocation {}: ", invocationId, ex);
        }
    }

    private void handleHeldJobs(int invocationId) throws DAOException {
        List<Job> failedJobs = jobDAO.getFailedJobsByInvocationID(invocationId);
        if (failedJobs == null || failedJobs.isEmpty()) return;

        logger.info("Handle Held jobs for invocation {}", invocationId);
        for (Job job : failedJobs) {
            GaswStatus status = job.getStatus();
            if (status == GaswStatus.ERROR_HELD || status == GaswStatus.STALLED_HELD) {
                resolveHeldJob(job, status);
            }
        }
    }

    private void resolveHeldJob(Job job, GaswStatus heldStatus) throws DAOException {
        boolean isStalled = heldStatus == GaswStatus.STALLED_HELD;
        GaswStatus newStatus = isStalled ? GaswStatus.STALLED : GaswStatus.ERROR;
        GaswExitCode exitCode = isStalled ? GaswExitCode.EXECUTION_STALLED : GaswExitCode.EXECUTION_FAILED;

        job.setBeingKilled(true);
        job.setStatus(newStatus);
        jobDAO.update(job);

        GaswOutput previousGaswOutput = gaswNotification.getGaswOutputFromLastFailedJob(job.getFileName() + ".jdl");
        if (previousGaswOutput != null) {
            logger.info("Getting previous StdOutErr files for held job instance: {}", job.getFileName());
        } else {
            logger.info("No previous StdOutErr files for held job instance: {}. Setting it to null.", job.getFileName());
        }

        GaswOutput output = new GaswOutput(
                job.getFileName() + ".jdl", exitCode, job.getExitMessage(), null,
                previousGaswOutput != null ? previousGaswOutput.getAppStdOut() : null,
                previousGaswOutput != null ? previousGaswOutput.getAppStdErr() : null,
                previousGaswOutput != null ? previousGaswOutput.getStdOut() : null,
                previousGaswOutput != null ? previousGaswOutput.getStdErr() : null);

        gaswNotification.addFinishedJob(output);
        logger.info("Resolved held job {}", job.getId());
    }

    private boolean hasFinished(Job job) throws DAOException {
        return jobMinorStatusDAO.getExecutionMinorStatus(job.getId())
                .stream()
                .anyMatch(ms -> ms.getStatus() == GaswMinorStatus.Finished);
    }

    private boolean hasFailedJobs(int invocationId) {
        try {
            return !jobDAO.getFailedJobsByInvocationID(invocationId).isEmpty();
        } catch (DAOException e) {
            logger.error("Error checking failures for invocation {}: ", invocationId, e);
            return false;
        }
    }

    private void logTimingsIfNecessary(CommandState state,Timings medians) {
        if (state.shouldLogTimings(medians, healingConfiguration.getStatsChangePercentage())) {
            state.updateLastLoggedTimings(medians);
            logger.info("Timings medians for [{}] — setup: {}, input: {}, execution: {}, output: {}",
                    state.getCommand(), medians.setup(), medians.input(), medians.execution(), medians.output());
        }
    }

    private String getFormattedNumber(double number, int decimals) {
        return String.format("%." + decimals + "f", number);
    }
}


