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

/**
 *
 * @author Rafael Ferreira da Silva
 */
public class HealingConstants {

    public final static String NAME = "Self-Healing";
    // Labels
    public final static String LAB_SLEEP_TIME = "plugin.healing.sleeptime";
    public final static String LAB_BLOCKED_COEFFICIENT = "plugin.healing.blocked.coefficient";
    public final static String LAB_MAX_REPLICAS = "plugin.healing.max.replicas";

    // percentage of change, 10 meaning change bigger than 10%
    public final static String LAB_STATS_CHANGE_PERCENTAGE = "plugin.healing.stats.changePercentage";

    // percentage of job errors above which all jobs get killed (stop condition)
    public final static String LAB_MAX_ERROR_JOB_PERCENTAGE = "plugin.healing.max.errorJobPercentage";
    // minimum number of invocations per workflow in STOP condition
    public final static String LAB_MIN_INVOCATIONS = "plugin.healing.min.invocations";
}
