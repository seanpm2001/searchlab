/**
 *  UserAudit
 *  Copyright 28.05.2022 by Michael Peter Christen, @orbiterlab
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.searchlab.audit;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import eu.searchlab.storage.io.ConcurrentIO;
import eu.searchlab.storage.io.GenericIO;
import eu.searchlab.storage.io.IOPath;
import eu.searchlab.storage.table.TimeSeriesTable;

/**
 * Class which stores information about the presence and actions of users
 */
public class UserAudit implements AuditTask {

    private final static String[] requestsViewColNames = new String[] {"id"};
    private final static String[] requestsMetaColNames = new String[] {"ip"};
    private final static String[] requestdDataColNames = new String[] {"requests"};


    private final static String[] visitorsViewColNames = new String[] {};
    private final static String[] visitorsMetaColNames = new String[] {};
    private final static String[] visitorsDataColNames = new String[] {"visitors"};

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, String>> lastSeen;
    private final TimeSeriesTable requestsTable, visitorsTable;
    private final ConcurrentIO cio;
    private final IOPath requestsIOp, visitorsIOp;

    public UserAudit(final GenericIO io, final IOPath requestsIOp, final IOPath visitorsIOp) throws IOException {
        this.cio = new ConcurrentIO(io, 10000);
        this.requestsIOp = requestsIOp;
        this.visitorsIOp = visitorsIOp;
        this.lastSeen = new ConcurrentHashMap<>();
        this.requestsTable =
                //(io.exists(requestsIOp)) ?
                //        TimeSeriesTable.readCSV(this.cio, requestsIOp) :
                        new TimeSeriesTable(requestsViewColNames, requestsMetaColNames, requestdDataColNames, false);
        this.visitorsTable =
                //(io.exists(visitorsIOp)) ?
                //        TimeSeriesTable.readCSV(this.cio, visitorsIOp) :
                        new TimeSeriesTable(visitorsViewColNames, visitorsMetaColNames, visitorsDataColNames, false);
    }

    public void event(final String id, final String ip) {
        ConcurrentHashMap<Long, String> u = this.lastSeen.get(id);
        if (u == null) {
            u = new ConcurrentHashMap<>();
            this.lastSeen.put(id, u);
        }
        u.put(System.currentTimeMillis(), ip);
    }

    @Override
    public void check() {

        // flush the lastSeen and update tables
        final long now = System.currentTimeMillis();
        final int sizeBeforeRequest = this.requestsTable.size();
        final int sizeBeforeVisitor = this.visitorsTable.size();
        final ConcurrentHashMap<String, ConcurrentHashMap<Long, String>> w = new ConcurrentHashMap<>();
        w.putAll(this.lastSeen);
        this.lastSeen.clear();
        w.forEach((id, tip) -> {
            final int count = tip.size();
            if (count > 0) {
                final String ip = tip.values().iterator().next();
                this.requestsTable.addValues(now, new String[] {id}, new String[] {ip}, new long[] {count});
            }
        });
        this.visitorsTable.addValues(now, new String[] {}, new String[] {}, new long[] {w.size()});

        // store tables
        int sizeAfter = this.requestsTable.size();
        if (sizeAfter > sizeBeforeRequest) {
            // store the table
            this.requestsTable.storeCSV(this.cio, this.requestsIOp);
        }

        sizeAfter = this.visitorsTable.size();
        if (sizeAfter > sizeBeforeVisitor) {
            // store the table
            this.visitorsTable.storeCSV(this.cio, this.visitorsIOp);
        }

    }
}
