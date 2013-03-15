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

import fr.insalyon.creatis.gasw.GaswException;
import fr.insalyon.creatis.gasw.bean.Job;
import fr.insalyon.creatis.gasw.dao.DAOException;
import fr.insalyon.creatis.gasw.dao.DAOFactory;
import fr.insalyon.creatis.gasw.dao.JobDAO;
import fr.insalyon.creatis.gasw.execution.GaswStatus;
import fr.insalyon.creatis.gasw.plugin.listener.healing.HealingConfiguration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.log4j.Logger;

/**
 *
 * @author Rafael Ferreira da Silva
 */
public class CommandState {

    private static final Logger logger = Logger.getLogger("fr.insalyon.creatis.gasw");
    private String command;
    private volatile boolean stop;
    private volatile List<Long> setupTimes;
    private volatile List<Long> inputTimes;
    private volatile List<Long> executionTimes;
    private volatile List<Long> outputTimes;

    public CommandState(String command) {

        this.command = command;
        this.stop = false;
        this.setupTimes = new ArrayList<Long>();
        this.inputTimes = new ArrayList<Long>();
        this.executionTimes = new ArrayList<Long>();
        this.outputTimes = new ArrayList<Long>();

        new ReplicationMonitor().start();
    }

    public void replicateJobs() {

        try {
            long setupMedian = getMedianValue(setupTimes);
            long inputMedian = getMedianValue(inputTimes);
            long executionMedian = getMedianValue(executionTimes);
            long outputMedian = getMedianValue(outputTimes);
            double blockedCoeff = HealingConfiguration.getInstance().getBlockedCoefficient();

            JobDAO jobDAO = DAOFactory.getDAOFactory().getJobDAO();

            for (Job runningJob : jobDAO.getRunningByCommand(command)) {

                boolean unstartedReplica = false;
                JobPhases bestJob = null;

                for (Job job : jobDAO.getActiveJobsByInvocationID(runningJob.getInvocationID())) {
                    if (job.getStatus() != GaswStatus.RUNNING
                            && job.getStatus() != GaswStatus.KILL
                            && job.getStatus() != GaswStatus.RESCHEDULE) {
                        unstartedReplica = true;
                        break;
                    }
                    JobPhases jobPhases = new JobPhases(job, setupMedian,
                            inputMedian, executionMedian, outputMedian);

                    if (bestJob == null) {
                        bestJob = jobPhases;

                    } else if (jobPhases.getEstimation() < bestJob.getEstimation()) {

                        if (jobPhases.getLastStatusCode() > bestJob.getLastStatusCode()
                                && ((double) bestJob.getEstimation()) / jobPhases.getEstimation() >= blockedCoeff) {
                            killReplica(bestJob.getJob());
                        }
                        bestJob = jobPhases;

                    } else if (((double) jobPhases.getEstimation()) / bestJob.getEstimation() >= blockedCoeff) {
                        killReplica(job);
                    }
                }
                if (unstartedReplica) {
                    continue;
                }

                if (bestJob != null && ((double) bestJob.getEstimation())
                        / (setupMedian + inputMedian + executionMedian + outputMedian) >= blockedCoeff) {

                    Job job = bestJob.getJob();
                    logger.info("[Healing] Replicating: " + job.getId());
                    job.setStatus(GaswStatus.REPLICATE);
                    jobDAO.update(job);
                }
            }
        } catch (DAOException ex) {
            // do nothing
        } catch (GaswException ex) {
            // do nothing
        }
    }

    private class ReplicationMonitor extends Thread {

        @Override
        public void run() {

            while (!stop) {
                try {
                    if (outputTimes.size() > 1) {
                        replicateJobs();
                    }
                    sleep(HealingConfiguration.getInstance().getSleepTime());

                } catch (GaswException ex) {
                    logger.error(ex);
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

    private void killReplica(Job job) throws DAOException {

        logger.info("[Healing] Killing replica: " + job.getId());
        job.setStatus(GaswStatus.KILL_REPLICA);
        DAOFactory.getDAOFactory().getJobDAO().update(job);
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

    /**
     * Terminates the monitor.
     */
    public void terminate() {

        this.stop = true;
    }
}
