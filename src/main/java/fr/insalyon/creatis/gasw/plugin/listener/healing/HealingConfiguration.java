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
package fr.insalyon.creatis.gasw.plugin.listener.healing;

import fr.insalyon.creatis.gasw.GaswConfiguration;
import fr.insalyon.creatis.gasw.GaswException;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HealingConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(HealingConfiguration.class);
    private static HealingConfiguration instance;
    private int sleepTime;
    private double blockedCoefficient;
    private int maxReplicas;
    private int statsChangePercentage;
    private double maxErrorJobPercentage;
    private double maxErrorInvocationPercentage;
    private int minInvocations;

    public static HealingConfiguration getInstance() {

        if (instance == null) {
            instance = new HealingConfiguration();
        }
        return instance;
    }

    private HealingConfiguration() {

        try {
            PropertiesConfiguration config = GaswConfiguration.getInstance().getPropertiesConfiguration();

            sleepTime = config.getInt(HealingConstants.LAB_SLEEP_TIME, 15) * 1000;
            blockedCoefficient = config.getDouble(HealingConstants.LAB_BLOCKED_COEFFICIENT, 2);
            maxReplicas = config.getInt(HealingConstants.LAB_MAX_REPLICAS, 2);
            statsChangePercentage = config.getInt(HealingConstants.LAB_STATS_CHANGE_PERCENTAGE, 10);
            maxErrorJobPercentage = config.getDouble(HealingConstants.LAB_MAX_ERROR_JOB_PERCENTAGE, 60);
            maxErrorInvocationPercentage = config.getDouble(HealingConstants.LAB_MAX_ERROR_INVOCATION_PERCENTAGE, 99.9);
            minInvocations = config.getInt(HealingConstants.LAB_MIN_INVOCATIONS, 100);

            config.setProperty(HealingConstants.LAB_SLEEP_TIME, sleepTime / 1000);
            config.setProperty(HealingConstants.LAB_BLOCKED_COEFFICIENT, blockedCoefficient);
            config.setProperty(HealingConstants.LAB_MAX_REPLICAS, maxReplicas);
            config.setProperty(HealingConstants.LAB_STATS_CHANGE_PERCENTAGE, statsChangePercentage);
            config.setProperty(HealingConstants.LAB_MAX_ERROR_JOB_PERCENTAGE, maxErrorJobPercentage);
            config.setProperty(HealingConstants.LAB_MAX_ERROR_INVOCATION_PERCENTAGE, maxErrorInvocationPercentage);
            config.setProperty(HealingConstants.LAB_MIN_INVOCATIONS, minInvocations);

            config.save();

        } catch (ConfigurationException | GaswException ex) {
            logger.error("Error initializing HealingConfiguration: ", ex);
        }
    }

    public int getSleepTime() {
        return sleepTime;
    }

    public double getBlockedCoefficient() {
        return blockedCoefficient;
    }

    public int getMaxReplicas() {
        return maxReplicas;
    }

    public int getStatsChangePercentage() {
        return statsChangePercentage;
    }

    public double getMaxErrorJobPercentage() {
        return maxErrorJobPercentage;
    }

    public double getMaxErrorInvocationPercentage() {
        return maxErrorInvocationPercentage;
    }

    public int getMinInvocations() {
        return minInvocations;
    }
}
