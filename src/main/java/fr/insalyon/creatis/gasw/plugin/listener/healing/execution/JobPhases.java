/* Copyright CNRS-CREATIS
 *
 * Rafael Ferreira da Silva
 * rafael.silva@creatis.insa-lyon.fr
 * http://www.rafaelsilva.com
 *
 * This software is a grid-enabled data-driven workflow manager and editor.
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
import fr.insalyon.creatis.gasw.bean.JobMinorStatus;
import fr.insalyon.creatis.gasw.dao.DAOException;
import fr.insalyon.creatis.gasw.dao.DAOFactory;
import fr.insalyon.creatis.gasw.execution.GaswMinorStatus;
import java.util.Date;

public class JobPhases {

    private Job job;
    private long startTime = 0;
    private long setupTime = 0;
    private long inputTime = 0;
    private long executionTime = 0;
    private long uploadTime = 0;
    private long estimation = 0;
    private GaswMinorStatus lastStatus = null;

    /**
     *
     * @param job
     * @param setupMedian
     * @param inputMedian
     * @param executionMedian
     * @param outputMedian
     */
    public JobPhases(Job job, long setupMedian, long inputMedian,
            long executionMedian, long outputMedian) throws GaswException {

        this.job = job;

        try {
            for (JobMinorStatus status : DAOFactory.getDAOFactory().getJobMinorStatusDAO().getExecutionMinorStatus(job.getId())) {

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
                        estimation = Math.max(setupTime, setupMedian) + inputMedian + executionMedian + outputMedian;
                        break;
                    case Inputs:
                        inputTime = currentTime - setupTime - startTime;
                        estimation += Math.max(inputTime, inputMedian) + executionMedian + outputMedian;
                        break;
                    case Application:
                        executionTime = currentTime - startTime - setupTime - inputTime;
                        estimation += Math.max(executionTime, executionMedian) + outputMedian;
                        break;
                    case Outputs:
                        uploadTime = currentTime - startTime - setupTime - inputTime - executionTime;
                        estimation += Math.max(uploadTime, outputMedian);
                }
            } else {
                estimation = setupMedian + inputMedian + executionMedian + outputMedian;
            }
        } catch (DAOException ex) {
            throw new GaswException(ex);
        }
    }

    public long getEstimation() {
        return estimation;
    }

    public int getLastStatusCode() {
        return lastStatus == null ? -1 : lastStatus.getStatusCode();
    }

    public Job getJob() {
        return job;
    }
}
