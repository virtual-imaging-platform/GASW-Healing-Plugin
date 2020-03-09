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

import fr.insalyon.creatis.gasw.GaswException;
import fr.insalyon.creatis.gasw.GaswOutput;
import fr.insalyon.creatis.gasw.bean.Job;
import fr.insalyon.creatis.gasw.bean.JobMinorStatus;
import fr.insalyon.creatis.gasw.dao.DAOException;
import fr.insalyon.creatis.gasw.dao.DAOFactory;
import fr.insalyon.creatis.gasw.dao.JobMinorStatusDAO;
import fr.insalyon.creatis.gasw.execution.GaswMinorStatus;
import fr.insalyon.creatis.gasw.plugin.ListenerPlugin;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import org.apache.log4j.Logger;

/**
 *
 * @author Rafael Ferreira da Silva
 */
@PluginImplementation
public class HealingListener implements ListenerPlugin {

    private static final Logger logger = Logger.getLogger("fr.insalyon.creatis.gasw");
    private volatile Map<String, CommandState> commandsMap;

    /**
     *
     * @return
     */
    @Override
    public String getPluginName() {

        return HealingConstants.NAME;
    }

    /**
     *
     * @throws GaswException
     */
    @Override
    public void load() throws GaswException {

        // fetch version from maven generated file
        logger.info("Loading Self-Healing GASW Plugin version "
                + getClass().getPackage().getImplementationVersion());
        
        HealingConfiguration.getInstance();
        commandsMap = new HashMap<String, CommandState>();
    }

    /**
     *
     * @return @throws GaswException
     */
    @Override
    public List<Class> getPersistentClasses() throws GaswException {

        List<Class> list = new ArrayList<Class>();
        return list;
    }

    /**
     *
     * @throws GaswException
     */
    @Override
    public void jobSubmitted(Job job) throws GaswException {

        String command = job.getCommand();
        if (!commandsMap.containsKey(command)) {
            commandsMap.put(command, new CommandState(command));
        }
    }

    /**
     *
     * @param gaswOutput
     * @throws GaswException
     */
    @Override
    public void jobFinished(GaswOutput gaswOutput) throws GaswException {
    }

    /**
     *
     * @param job
     * @throws GaswException
     */
    @Override
    public void jobStatusChanged(Job job) throws GaswException {
    }

    /**
     *
     * @param jobMinorStatus
     * @throws GaswException
     */
    @Override
    public void jobMinorStatusReported(JobMinorStatus jobMinorStatus) throws GaswException {

        try {
            logger.info("[Healing] Minor Status Reported: " + jobMinorStatus.getJob().getId() + " - " + jobMinorStatus.getStatus().name());
            Job job = jobMinorStatus.getJob();
            CommandState cs;
            if (commandsMap.containsKey(job.getCommand())) {
                cs = commandsMap.get(job.getCommand());
            } else {
                cs = new CommandState(job.getCommand());
                commandsMap.put(job.getCommand(), cs);
            }

            JobMinorStatusDAO minorStatusDAO = DAOFactory.getDAOFactory().getJobMinorStatusDAO();

            switch (jobMinorStatus.getStatus()) {
                case Inputs:
                    cs.addSetupTime(minorStatusDAO.getDateDiff(job.getId(), GaswMinorStatus.Started, GaswMinorStatus.Inputs));
                    break;
                case Application:
                    cs.addDownloadTime(minorStatusDAO.getDateDiff(job.getId(), GaswMinorStatus.Inputs, GaswMinorStatus.Application));
                    break;
                case Outputs:
                    cs.addExecutionTime(minorStatusDAO.getDateDiff(job.getId(), GaswMinorStatus.Application, GaswMinorStatus.Outputs));
                    break;
                case Finished:
                    cs.addUploadTime(minorStatusDAO.getDateDiff(job.getId(), GaswMinorStatus.Outputs, GaswMinorStatus.Finished));
                    break;
                default:
            }
        } catch (DAOException ex) {
            logger.error("[Healing] Error updating minor status", ex);
        }
    }

    /**
     *
     * @throws GaswException
     */
    @Override
    public void terminate() throws GaswException {

        for (CommandState cs : commandsMap.values()) {
            cs.terminate();
        }
    }
}
