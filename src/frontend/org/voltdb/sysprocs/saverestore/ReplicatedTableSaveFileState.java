/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.sysprocs.saverestore;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;
import org.voltdb.ParameterSet;
import org.voltdb.VoltDB;
import org.voltdb.VoltTableRow;
import org.voltdb.VoltSystemProcedure.SynthesizedPlanFragment;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Host;
import org.voltdb.catalog.Partition;
import org.voltdb.catalog.Site;
import org.voltdb.catalog.Table;
import org.voltdb.sysprocs.SysProcFragmentId;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.HStore;
import edu.brown.hstore.HStoreSite;

public class ReplicatedTableSaveFileState extends TableSaveFileState
{
    private static final Logger LOG = Logger.getLogger(ReplicatedTableSaveFileState.class);
    
    ReplicatedTableSaveFileState(String tableName, int allowExport)
    {
        super(tableName, allowExport);
    }

    @Override
    void addHostData(VoltTableRow row) throws IOException
    {
        assert(row.getString("TABLE").equals(getTableName()));

        checkSiteConsistency(row); // throws if inconsistent
        // XXX this cast should be safe; site_ids are ints but get
        // promoted to long in the VoltTable.row.getLong return
        m_hostsWithThisTable.add((int) row.getLong("CURRENT_HOST_ID"));
    }

    @Override
    public boolean isConsistent()
    {
        // XXX right now there is nothing to check across all rows
        return true;
    }

    public Set<Integer> getHostsWithThisTable() {
        return m_hostsWithThisTable;
    }

    @Override
    public SynthesizedPlanFragment[]
    generateRestorePlan(Table catalogTable)
    {            
        LOG.info("Total partitions for Replicated Table: " + getTableName());
        Cluster catalog_clus = CatalogUtil.getCluster(HStore.instance().getCatalog());
        Host[] hosts_value = catalog_clus.getHosts().values();
        
        for (int hostId = 0; hostId < m_hostsWithThisTable.size(); hostId++) {  
            for (int i = 0; i < hosts_value.length; i++)
                if (hosts_value[i].getId() == hostId) {
                    Collection<Partition> siteList = CatalogUtil.getPartitionsForHost(hosts_value[i]);
                    for (Partition st : siteList) {
                            m_sitesWithThisTable.add(st.getId());
                    }      
                }
//            m_sitesWithThisTable.addAll(VoltDB.instance().getCatalogContext().
//                                        siteTracker.getLiveExecutionSitesForHost(hostId));
        }

        SynthesizedPlanFragment[] restore_plan = null;
        if (catalogTable.getIsreplicated())
        {
            restore_plan = generateReplicatedToReplicatedPlan();
        }
        else
        {
            // XXX Not implemented until we're going to support catalog changes
        }
        return restore_plan;
    }

    private void checkSiteConsistency(VoltTableRow row) throws IOException
    {
        if (!row.getString("IS_REPLICATED").equals("TRUE"))
        {
            String error = "Table: " + getTableName() + " was replicated " +
            "but has a savefile which indicates partitioning at site: " +
            row.getLong("CURRENT_HOST_ID");
            throw new IOException(error);
        }
    }

    private SynthesizedPlanFragment[]
    generateReplicatedToReplicatedPlan()
    {
        SynthesizedPlanFragment[] restore_plan = null;
//        Cluster catalog_clus = CatalogUtil.getCluster(HStore.instance().getCatalog());
//        Host[] hosts_value = catalog_clus.getHosts().values();        
//        for (int i =0; i < hosts_value.length; i++) {
//            Collection<Partition> siteList = CatalogUtil.getPartitionsForHost(hosts_value[i]);         
//            for (Partition site: siteList) {
//                execution_site_ids.add(site.getId());
//            }
//        }
        Collection<Integer> execution_site_ids = CatalogUtil.getAllPartitionIds(HStore.instance().getCatalog());
//        Set<Integer> execution_site_ids = 
//            VoltDB.instance().getCatalogContext().siteTracker.getExecutionSiteIds();
        Set<Integer> sites_missing_table =
            getSitesMissingTable(execution_site_ids);
        // not sure we want to deal with handling expected load failures,
        // so let's send an individual load to each site with the table
        // and then pick sites to send the table to those without it
        restore_plan =
            new SynthesizedPlanFragment[execution_site_ids.size() + 1];
        int restore_plan_index = 0;
        for (Integer site_id : m_sitesWithThisTable)
        {
            restore_plan[restore_plan_index] =
                constructLoadReplicatedTableFragment();
            restore_plan[restore_plan_index].destPartitionId = site_id;
            ++restore_plan_index;
        }
        for (Integer site_id : sites_missing_table)
        {
            int source_site_id =
                m_sitesWithThisTable.iterator().next(); // XXX hacky
            restore_plan[restore_plan_index] =
                constructDistributeReplicatedTableFragment(source_site_id,
                                                           site_id);
            ++restore_plan_index;
        }
        assert(restore_plan_index == execution_site_ids.size());
        restore_plan[restore_plan_index] =
            constructLoadReplicatedTableAggregatorFragment();
        return restore_plan;
    }

    private Set<Integer> getSitesMissingTable(Collection<Integer> clusterSiteIds)
    {
        Set<Integer> sites_missing_table = new HashSet<Integer>();
        for (int site_id : clusterSiteIds)
        {
            if (!m_sitesWithThisTable.contains(site_id))
            {
                sites_missing_table.add(site_id);
            }
        }
        return sites_missing_table;
    }

    private SynthesizedPlanFragment
    constructLoadReplicatedTableFragment()
    {
        int result_dependency_id = getNextDependencyId();
        SynthesizedPlanFragment plan_fragment = new SynthesizedPlanFragment();
        plan_fragment.fragmentId =
            SysProcFragmentId.PF_restoreLoadReplicatedTable;
        plan_fragment.multipartition = false;
        plan_fragment.outputDependencyIds = new int[]{ result_dependency_id };
        plan_fragment.inputDependencyIds = new int[] {};
        addPlanDependencyId(result_dependency_id);
        ParameterSet params = new ParameterSet();
        params.setParameters(getTableName(), result_dependency_id, m_allowExport);
        plan_fragment.parameters = params;
        return plan_fragment;
    }

    private SynthesizedPlanFragment
    constructDistributeReplicatedTableFragment(int sourceSiteId,
                                               int destinationSiteId)
    {
        int result_dependency_id = getNextDependencyId();
        SynthesizedPlanFragment plan_fragment = new SynthesizedPlanFragment();
        plan_fragment.fragmentId =
            SysProcFragmentId.PF_restoreDistributeReplicatedTable;
        plan_fragment.multipartition = false;
        plan_fragment.destPartitionId = sourceSiteId;
        plan_fragment.outputDependencyIds = new int[]{ result_dependency_id };
        plan_fragment.inputDependencyIds = new int[] {};
        addPlanDependencyId(result_dependency_id);
        ParameterSet params = new ParameterSet();
        params.setParameters(getTableName(), destinationSiteId,
                             result_dependency_id, m_allowExport);
        plan_fragment.parameters = params;
        return plan_fragment;
    }

    private SynthesizedPlanFragment
    constructLoadReplicatedTableAggregatorFragment()
    {
        int result_dependency_id = getNextDependencyId();
        SynthesizedPlanFragment plan_fragment = new SynthesizedPlanFragment();
        plan_fragment.fragmentId =
            SysProcFragmentId.PF_restoreLoadReplicatedTableResults;
        plan_fragment.multipartition = false;
        plan_fragment.outputDependencyIds = new int[]{ result_dependency_id };
        plan_fragment.inputDependencyIds = getPlanDependencyIds();
        setRootDependencyId(result_dependency_id);
        ParameterSet params = new ParameterSet();
        params.setParameters(result_dependency_id);
        plan_fragment.parameters = params;
        return plan_fragment;
    }

    private final Set<Integer> m_hostsWithThisTable = new HashSet<Integer>();
    private final Set<Integer> m_sitesWithThisTable = new HashSet<Integer>();
}
