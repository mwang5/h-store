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

package org.voltdb.sysprocs;

import java.util.HashMap;
import java.util.TreeSet;
import java.util.Iterator;

import org.voltdb.sysprocs.saverestore.SnapshotUtil;

import edu.brown.hstore.HStore;
import edu.brown.hstore.PartitionExecutor;

/**
 * The snapshot registry contains information about snapshots that executed
 * while the system was running.
 *
 */
public class SnapshotRegistry {
    private static final int m_maxStatusHistory = 10;

    private static final TreeSet<Snapshot> m_snapshots = new TreeSet<Snapshot>(
            new java.util.Comparator<Snapshot>() {

                @Override
                public int compare(Snapshot o1, Snapshot o2) {
                    return new Long(o1.timeStarted).compareTo(o2.timeStarted);
                }

            });

    public static class Snapshot {
        public final long timeStarted;
        public final long timeFinished;

        public final String path;
        public final String nonce;
        public final boolean result; //true success, false failure

        public final long bytesWritten;

        private final HashMap< String, Table> tables = new HashMap< String, Table>();

        private Snapshot(long startTime, int hostId, String path, String nonce,
                         org.voltdb.catalog.Table tables[], PartitionExecutor ee) {
            timeStarted = startTime;
            this.path = path;
            this.nonce = nonce;
            timeFinished = 0;
            synchronized (this.tables) {
                for (org.voltdb.catalog.Table table : tables) {
                    String filename =
                        SnapshotUtil.constructFilenameForTable(table,
                                                               nonce,
                                                               Integer.toString(hostId), ee);
                    this.tables.put(table.getTypeName(), new Table(table.getTypeName(), filename));
                }
            }
            result = false;
            bytesWritten = 0;
        }

        private Snapshot(Snapshot incomplete, long timeFinished) {
            timeStarted = incomplete.timeStarted;
            path = incomplete.path;
            nonce = incomplete.nonce;
            this.timeFinished = timeFinished;
            synchronized (tables) {
                tables.putAll(incomplete.tables);
            }
            long bytesWritten = 0;
            boolean result = true;
            for (Table t : tables.values()) {
                bytesWritten += t.size;
                if (t.error != null) {
                    result = false;
                }
            }
            this.bytesWritten = bytesWritten;
            this.result = result;
        }

        public interface TableUpdater {
            public Table update(Table t);
        }

        public interface TableIterator {
            public void next(Table t);
        }

        public void iterateTables(TableIterator ti) {
            synchronized (tables) {
                for (Table t : tables.values()) {
                    ti.next(t);
                }
            }
        }

        public void updateTable(String name, TableUpdater tu) {
            synchronized (tables) {
                assert(tables.get(name) != null);
                tables.put(name, tu.update(tables.get(name)));
            }
        }

        public class Table {
            public final String name;
            public final String filename;
            public final long size;
            public final Exception error;

            private Table(String name, String filename) {
                this.name = name;
                this.filename = filename;
                size = 0;
                error = null;
            }

            public Table(Table t, long size, Exception error) {
                this.name = t.name;
                this.filename = t.filename;
                this.size = size;
                this.error = error;
            }
        }
    }

    public static synchronized Snapshot startSnapshot(long startTime, int hostId, String path, String nonce, org.voltdb.catalog.Table tables[], PartitionExecutor ee) {
        final Snapshot s = new Snapshot(startTime, hostId, path, nonce, tables, ee);

        m_snapshots.add(s);
        if (m_snapshots.size() > m_maxStatusHistory) {
            Iterator<Snapshot> iter = m_snapshots.iterator();
            iter.next();
            iter.remove();
        }

        return s;
    }

    public static synchronized void discardSnapshot(Snapshot s) {
        m_snapshots.remove(s);
    }

    public static synchronized Snapshot finishSnapshot(Snapshot incomplete) {
        boolean removed = m_snapshots.remove(incomplete);
        assert(removed);
        final Snapshot completed = new Snapshot(incomplete, System.currentTimeMillis());
        m_snapshots.add(completed);
        return completed;
    }

    public static synchronized TreeSet<Snapshot> getSnapshotHistory() {
        return new TreeSet<Snapshot>(m_snapshots);
    }

    public static synchronized void clear() {
        m_snapshots.clear();
    }
}
