/* Copyright CNRS-CREATIS
 *
 * Rafael Ferreira da Silva
 * rafael.silva@creatis.insa-lyon.fr
 * http://www.rafaelsilva.com
 *
 * This software is governed by the CeCILL  license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL license and that you accept its terms.
 */
package fr.insalyon.creatis.gasw.plugin.listener.healing.execution;

import fr.insalyon.creatis.gasw.*;
import fr.insalyon.creatis.gasw.bean.*;
import fr.insalyon.creatis.gasw.dao.*;
import fr.insalyon.creatis.gasw.execution.*;
import fr.insalyon.creatis.gasw.plugin.listener.healing.HealingConfiguration;

import java.util.*;

import org.apache.log4j.Logger;

import static java.lang.Thread.sleep;

/**
 *
 * @author Rafael Ferreira da Silva
 */
public class CommandState {

    private static final Logger logger = Logger.getLogger("fr.insalyon.creatis.gasw");
    private String command;
    private volatile boolean stop;
    private volatile boolean killAllJobs;
    private volatile List<Long> setupTimes;
    private volatile List<Long> inputTimes;
    private volatile List<Long> executionTimes;
    private volatile List<Long> outputTimes;
    private volatile double jobErrorRate;
    private volatile double invocationPartialErrorRate;

    private Map<String,Long> lastLoggedTimes;

    public CommandState(String command) {

        this.command = command;
        this.stop = false;
        this.setupTimes = new ArrayList<Long>();
        this.inputTimes = new ArrayList<Long>();
        this.executionTimes = new ArrayList<Long>();
        this.outputTimes = new ArrayList<Long>();
        this.jobErrorRate = 0.0 ;
        this.invocationPartialErrorRate = 0.0 ;
        this.killAllJobs = false;

        this.lastLoggedTimes = new HashMap<>();

        new ReplicationMonitor().start();
    }

    public void replicateJobs() {

        try {
            long setupMedian = getMedianValue(setupTimes);
            long inputMedian = getMedianValue(inputTimes);
            long executionMedian = getMedianValue(executionTimes);
            long outputMedian = getMedianValue(outputTimes);
            logTimesIfNecessary(setupMedian, inputMedian, executionMedian, outputMedian);

            JobDAO jobDAO = DAOFactory.getDAOFactory().getJobDAO();
            for (Job runningJob : jobDAO.getRunningByCommand(command)) {

                List<Job> activeJobs = jobDAO.getActiveJobsByInvocationID(runningJob.getInvocationID());
                List<Job> failedJobs = jobDAO.getFailedJobsByInvocationID(runningJob.getInvocationID());

                // Only heal if all the active jobs are RUNNING and if
                // none is an temporary state

                if (canDoHealingForJobs(activeJobs, failedJobs)) {
                    // if OK, do the healing on the running jobs
                    doHealing(activeJobs, failedJobs, setupMedian, inputMedian, executionMedian, outputMedian);
                }


            }
        } catch (DAOException ex) {
            logger.error("[Healing] Error looking for jobs to replicate: ", ex);
        }
    }

    private List<Job> filterRunningJobs(List<Job> allInvocationJobs) {
        List<Job> runningJobs = new ArrayList<> (allInvocationJobs);
        runningJobs.removeIf(job -> job.getStatus() != GaswStatus.RUNNING);
        return runningJobs;
    }

    private boolean canDoHealingForJobs(
            List<Job> activeJobs, List<Job> failedJobs) throws DAOException {

        // do NOT do healing when
        // - a job is in a temporary state
        //      (replicating, restarting, finishing, being killed)
        // - a job is active but not running (submitted, queued)

        // first check on the jobs internal information to avoid database access
        for (Job job : activeJobs) {
            if (job.isReplicating() || job.getStatus() != GaswStatus.RUNNING) {
                return false;
            }
        }
        for (Job job : failedJobs) {
            if (job.isReplicating()) {
                return false;
            }
        }
        // to do database access only when necessary, check for minor statuses
        // only after checking all jobs internal information
        for (Job job : activeJobs) {
            if (hasFinished(job)) {
                return false;
            }
        }

        return true;
    }

    private boolean hasFinished(Job job) throws DAOException {
        JobMinorStatusDAO minorStatusDAO = DAOFactory.getDAOFactory().getJobMinorStatusDAO();
        List<JobMinorStatus> minorStatuses = minorStatusDAO.getExecutionMinorStatus(job.getId());
        return minorStatuses.stream().anyMatch(ms -> ms.getStatus() == GaswMinorStatus.Finished);
    }

    private void doHealing(List<Job> jobs, List<Job> failedJobs,
                           long setupMedian, long inputMedian,
                           long executionMedian, long outputMedian) {
        try {
            double blockedCoeff = HealingConfiguration.getInstance().getBlockedCoefficient();
            JobDAO jobDAO = DAOFactory.getDAOFactory().getJobDAO();

            JobPhases bestJob = null;

            for (Job job : jobs) {
                JobPhases jobPhases = new JobPhases(job, setupMedian,
                        inputMedian, executionMedian, outputMedian);

                if (bestJob == null) {
                    bestJob = jobPhases;
                } else if (jobPhases.getEstimation() < bestJob.getEstimation()) {
                    JobPhases formerBestJob = bestJob;
                    bestJob = jobPhases;
                    killReplicaIfNecessary(formerBestJob, bestJob);
                } else {
                    killReplicaIfNecessary(jobPhases, bestJob);
                }
            }
            if (jobs.size() < HealingConfiguration.getInstance().getMaxReplicas()
                    && (failedJobs.size() - 1) < GaswConfiguration.getInstance().getDefaultRetryCount()
                    && bestJob != null && ((double) bestJob.getEstimation())
                    / (setupMedian + inputMedian + executionMedian + outputMedian) >= blockedCoeff) {

                Job job = bestJob.getJob();
                logger.info("[Healing] Replicating: " + job.getId() + " (jobEstimation: " + bestJob.getEstimation() + " ) ");
                job.setStatus(GaswStatus.REPLICATE);
                jobDAO.update(job);
            }
        } catch (DAOException | GaswException ex) {
            logger.error("[Healing] Error looking for jobs to replicate: ", ex);
        }
    }

    private void killReplicaIfNecessary(
            JobPhases jobToEvaluatePhase,
            JobPhases bestJobPhase) throws DAOException {
        if (jobToEvaluatePhase.getLastStatusCode() >= bestJobPhase.getLastStatusCode()) {
            // do nothing if the job is not in an equal or more advanced state
            return;
        }
        double blockedCoeff = HealingConfiguration.getInstance().getBlockedCoefficient();
        if (((double) jobToEvaluatePhase.getEstimation()) / bestJobPhase.getEstimation() >= blockedCoeff) {
            Job jobToEvaluate = jobToEvaluatePhase.getJob();
            Job bestJob = bestJobPhase.getJob();
            logger.info("[Healing] Killing replica: " + jobToEvaluate.getId() +
                    " because " + bestJob.getId() + " is better");
            logger.info("[Healing] Status : " + jobToEvaluatePhase.getLastStatusCode() +
                    " vs " + bestJobPhase.getLastStatusCode());
            logger.info("[Healing] Estimations : " + jobToEvaluatePhase.getEstimation() +
                    " vs " + bestJobPhase.getEstimation());
            jobToEvaluate.setStatus(GaswStatus.KILL_REPLICA);
            DAOFactory.getDAOFactory().getJobDAO().update(jobToEvaluate);
        }
    }

    private void logTimesIfNecessary(
            long setupMedian, long inputMedian,
            long executionMedian, long outputMedian) {

        if (lastLoggedTimes.isEmpty()) {
            printAndUpdateStats(
                    setupMedian, inputMedian, executionMedian, outputMedian);
        } else {
            boolean needToLog = false;
            int percentage =
                    HealingConfiguration.getInstance().getStatsChangePercentage();
            if (isChangeGreaterThanPercentage(
                    lastLoggedTimes.get("setup"), setupMedian, percentage)) {
                needToLog = true;
            } else if (isChangeGreaterThanPercentage(
                    lastLoggedTimes.get("input"), inputMedian, percentage)) {
                needToLog = true;
            } else if (isChangeGreaterThanPercentage(
                    lastLoggedTimes.get("execution"), executionMedian, percentage)) {
                needToLog = true;
            } else if (isChangeGreaterThanPercentage(
                    lastLoggedTimes.get("output"), outputMedian, percentage)) {
                needToLog = true;
            }

            if (needToLog) {
                printAndUpdateStats(
                        setupMedian, inputMedian, executionMedian, outputMedian);
            }
        }
    }

    private boolean isChangeGreaterThanPercentage(
            long v1, long v2, int percentage) {

        double ratio = 1 -
                percentage / 100.;
        double max = Math.max(v1, v2);
        double min = Math.min(v1, v2);
        return (min/max < ratio);
    }

    private void printAndUpdateStats(
            long setupMedian, long inputMedian,
            long executionMedian, long outputMedian) {

        lastLoggedTimes.put("setup", setupMedian);
        lastLoggedTimes.put("input", inputMedian);
        lastLoggedTimes.put("execution", executionMedian);
        lastLoggedTimes.put("output", outputMedian);

        logger.info("[Healing] [Logging stats] setupMedian: " + setupMedian +
                " ; inputMedian: " + inputMedian +
                " ; executionMedian: " + executionMedian +
                " ; outputMedian: " + outputMedian);
    }

    private class ReplicationMonitor extends Thread {

        @Override
        public void run() {

            while (!stop) {
                try {
                    if (killAllJobs) {
                        killAllJobs();
                    } else {
                        if (outputTimes.size() > 1) {
                            replicateJobs();
                        }
                    }
                    sleep(HealingConfiguration.getInstance().getSleepTime());
                    
                } catch (InterruptedException ex) {
                    logger.error(ex);
                }
            }
        }
    }

    private long getMedianValue(List<Long> list) {

        Collections.sort(list);
        if (list.size() % 2 == 1) {
            return list.get(list.size() / 2);

        } else {
            int index = list.size() / 2;
            return (list.get(index - 1) + list.get(index)) / 2;
        }
    }

    public void addSetupTime(long time) {
        this.setupTimes.add(time);
    }

    public void addDownloadTime(long time) {
        this.inputTimes.add(time);
    }

    public void addExecutionTime(long time) {
        this.executionTimes.add(time);
    }

    public void addUploadTime(long time) {
        this.outputTimes.add(time);
    }

    private void computeJobErrorRate() throws DAOException {
        JobDAO jobDAO = DAOFactory.getDAOFactory().getJobDAO();
        this.jobErrorRate = 100.0 * jobDAO.getFailedByCommand(this.command).size() / jobDAO.getJobsByCommand(this.command).size();
        logger.info("[Healing] Updated the jobErrorRate to " + this.jobErrorRate);

    }

    private void computeInvocationPartialErrorRate() throws DAOException {
        // TODO : after further analysis, also consider jobs running for more than MAX hours when computing invocationPartialErrorRate
        int failures = 0;
        JobDAO jobDAO = DAOFactory.getDAOFactory().getJobDAO();
        List<Integer> invocationIDs = jobDAO.getInvocationsByCommand(this.command);
        for (int invocation : invocationIDs) {
            if (jobDAO.getFailedJobsByInvocationID(invocation).size() >= 1) {
                failures++;
            }
        }
        this.invocationPartialErrorRate = 100.0 * failures / invocationIDs.size();
        logger.info("[Healing] Updated the invocationPartialErrorRate to " + this.invocationPartialErrorRate);

    }

    public void updateErrorRatesAndKillDecision() throws DAOException {
        computeJobErrorRate();
        computeInvocationPartialErrorRate();
        if (DAOFactory.getDAOFactory().getJobDAO().getInvocationsByCommand(this.command).size() >= HealingConfiguration.getInstance().getMinInvocations() ) {
            if (this.jobErrorRate >= HealingConfiguration.getInstance().getMaxErrorJobPercentage() ||
                    this.invocationPartialErrorRate >= HealingConfiguration.getInstance().getMaxErrorInvocationPercentage()) {
                this.killAllJobs = true;
                logger.info("[Healing] Attention, updating killing decision to true. Nm min invocations are "+
                        HealingConfiguration.getInstance().getMinInvocations()+" , job error rate is "+
                        this.jobErrorRate + " and invocation error rate is "+ this.invocationPartialErrorRate);
            }
        }

    }

    private void killAllJobs() {
        logger.info("[Healing] Killing all jobs of type " + this.command);
        try {
            JobDAO jobDAO = DAOFactory.getDAOFactory().getJobDAO();
            List<Integer> invocationIDs = jobDAO.getInvocationsByCommand(this.command);
            for (int invocation : invocationIDs) {
                killInvocationJobs(invocation);
            }
            if(jobDAO.getActiveJobs().isEmpty()){
                //This is only needed because of the Moteur bug preventing the workflow from finishing when there are no jobs left
                //TODO: remove this when the Moteur bug is fixed
                logger.info("[Healing] Attention, no active jobs left, stopping the healing now.");
                terminate();
            }
        } catch (DAOException ex) {
            logger.error("[Healing] Error killing jobs: ", ex);
        }
    }

    private void killInvocationJobs (int invocation) {
        logger.info("[Healing] Killing jobs of invocation " + invocation);
        try {
            JobDAO jobDAO = DAOFactory.getDAOFactory().getJobDAO();
            GaswStatus status = GaswStatus.KILL;
            List<Job> activeJobs = jobDAO.getActiveJobsByInvocationID(invocation);
            if ((activeJobs != null) && (!activeJobs.isEmpty())) {
                for (Job job : activeJobs) {
                    job.setStatus(status);
                    job.setBeingKilled(true);
                    jobDAO.update(job);
                    logger.info("[Healing] Setting status of job " + job.getId() + " to " + status);
                    //all subsequent jobs are replica, so kill them as such
                    status = GaswStatus.KILL_REPLICA;
                }
            } else {
                if (jobDAO.getNumberOfCompletedJobsByInvocationID(invocation) == 0) {
                    handleHeldJobs(invocation);
                }
            }

        } catch (DAOException ex) {
            logger.error("[Healing] Error killing jobs for invocation : " + invocation, ex);
        }
    }

    private void handleHeldJobs (int invocationID){
        logger.info("[Healing] Handle Held jobs for invocation " + invocationID);
        try {
            JobDAO jobDAO = DAOFactory.getDAOFactory().getJobDAO();
            List<Job> failedJobs = jobDAO.getFailedJobsByInvocationID(invocationID);
            if ((failedJobs!=null) && (!failedJobs.isEmpty())) {
                for (Job job : failedJobs) {
                    GaswStatus jobStatus = job.getStatus();
                    if ((jobStatus == GaswStatus.ERROR_HELD) || (jobStatus == GaswStatus.STALLED_HELD)) {
                        GaswStatus newStatus = GaswStatus.ERROR;
                        GaswExitCode exitCode = GaswExitCode.EXECUTION_FAILED;
                        if (jobStatus == GaswStatus.STALLED_HELD) {
                            newStatus = GaswStatus.STALLED;
                            exitCode = GaswExitCode.EXECUTION_STALLED;
                        }
                        job.setBeingKilled(true);
                        job.setStatus(newStatus);
                        jobDAO.update(job);
                        GaswOutput gaswOutput = new GaswOutput(job.getFileName() + ".jdl", exitCode, job.getExitMessage(),
                                null, null, null, null, null);
                        GaswNotification.getInstance().addFinishedJob(gaswOutput);
                        logger.info("[Healing] Handled Held job " + job.getId());
                    }
                }
            }
        } catch (DAOException ex) {
            logger.error("[Healing] Error handling held job: ", ex);
        }
    }

        /**
     * Terminates the monitor.
     */
    public void terminate() {

        this.stop = true;
    }
}
