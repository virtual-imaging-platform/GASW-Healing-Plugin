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
package fr.insalyon.creatis.gasw.plugin.listener.healing;

import fr.insalyon.creatis.gasw.GaswExitCode;
import fr.insalyon.creatis.gasw.GaswOutput;
import fr.insalyon.creatis.gasw.bean.Job;
import fr.insalyon.creatis.gasw.bean.JobMinorStatus;
import fr.insalyon.creatis.gasw.dao.DAOException;
import fr.insalyon.creatis.gasw.dao.JobMinorStatusDAO;
import fr.insalyon.creatis.gasw.execution.GaswMinorStatus;
import fr.insalyon.creatis.gasw.plugin.ListenerPlugin;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandState;
import java.util.regex.Pattern;

import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandStateRegistry;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.HealingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@Service
public class HealingListener implements ListenerPlugin {

    private static final Pattern JOB_ID_PATTERN = Pattern.compile("-[0-9]+(\\.jdl)?$");

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final JobMinorStatusDAO jobMinorStatusDAO;
    private final HealingService healingService;
    private final CommandStateRegistry commandStateRegistry;

    public HealingListener(JobMinorStatusDAO jobMinorStatusDAO, HealingService healingService, CommandStateRegistry commandStateRegistry) {
        this.jobMinorStatusDAO = jobMinorStatusDAO;
        this.healingService = healingService;
        this.commandStateRegistry = commandStateRegistry;
    }

    @PostConstruct
    public void start() {
        // fetch version from maven generated file
        logger.info("Loading Self-Healing GASW Plugin version {}",
                getClass().getPackage().getImplementationVersion());
    }

    @Override
    public String getName() {
        return HealingConstants.NAME;
    }

    @Override
    public String getEntityPackage() {
        return HealingConstants.ENTITY_PACKAGE;
    }

    @Override
    public void jobSubmitted(Job job) {
        commandStateRegistry.getOrCreate(job.getCommand());
    }

    @Override
    public void jobFinished(GaswOutput gaswOutput) {
        logger.info("Job {} finished with exit code {}", gaswOutput.getJobID(), gaswOutput.getExitCode());
        // Attention, gaswOutput.getJobID() returns the Moteur job ID in the format command-4072786226984043.jdl
        String jobID = gaswOutput.getJobID();
        String command = JOB_ID_PATTERN.matcher(jobID).replaceAll("");
        CommandState cs = commandStateRegistry.getOrCreate(command);
        GaswExitCode code = gaswOutput.getExitCode();
        if (code != GaswExitCode.SUCCESS && code != GaswExitCode.EXECUTION_CANCELED) {
            try {
                healingService.computeAndUpdateErrorRates(cs);
            } catch (DAOException ex) {
                logger.error("Error computing error rates", ex);
            }
        }
    }

    @Override
    public void jobStatusChanged(Job job) {}

    @Override
    public void jobMinorStatusReported(JobMinorStatus jobMinorStatus) {
        Job job = jobMinorStatus.getJob();
        logger.info("Minor Status Reported: {} - {}", job.getId(), jobMinorStatus.getStatus().name());
        CommandState cs = commandStateRegistry.getOrCreate(job.getCommand());
        try {
            switch (jobMinorStatus.getStatus()) {
                case Inputs:
                    cs.addSetupTime(jobMinorStatusDAO.getDateDiff(job.getId(), GaswMinorStatus.Started, GaswMinorStatus.Inputs));
                    break;
                case Application:
                    cs.addDownloadTime(jobMinorStatusDAO.getDateDiff(job.getId(), GaswMinorStatus.Inputs, GaswMinorStatus.Application));
                    break;
                case Outputs:
                    cs.addExecutionTime(jobMinorStatusDAO.getDateDiff(job.getId(), GaswMinorStatus.Application, GaswMinorStatus.Outputs));
                    break;
                case Finished:
                    cs.addUploadTime(jobMinorStatusDAO.getDateDiff(job.getId(), GaswMinorStatus.Outputs, GaswMinorStatus.Finished));
                    break;
                default:
            }
        } catch (DAOException ex) {
            logger.error("Error updating minor status for job {}", job.getId(), ex);
        }
    }

    @Override
    public void terminate() {}

}
