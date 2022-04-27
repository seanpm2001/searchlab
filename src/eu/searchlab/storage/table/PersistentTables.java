/**
 *  PersistentTables
 *  Copyright 09.10.2021 by Michael Peter Christen, @orbiterlab
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

package eu.searchlab.storage.table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONTokener;

import eu.searchlab.storage.io.GenericIO;
import eu.searchlab.storage.io.IOPath;
import eu.searchlab.tools.Logger;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.Source;
import tech.tablesaw.io.json.JsonReadOptions;
import tech.tablesaw.io.json.JsonReader;

/**
 * This implements a table repository with no intent to provide storage to the tables.
 * Tables may be injected into this repository which can be updated and stored as
 * a side effect.
 */
public class PersistentTables {

    private final Map<String, IndexedTable> indexes;
    private String urlstub;
    private GenericIO io;
    private IOPath iop;

    /**
     * create an empty TableServer
     */
    public PersistentTables() {
        this.indexes = new HashMap<>();
        this.urlstub = null;
    }

    /**
     * Create access to a hosted TableServer
     * @param urlstub
     * @return this
     */
    public PersistentTables connect(final String urlstub) {
        this.urlstub = urlstub;
        return this;
    }

    /**
     * Create access to a GenericIO location
     * @param io
     * @param iop
     * @return this
     */
    public PersistentTables connect(final GenericIO io, final IOPath iop) {
        this.io = io;
        this.iop = iop;
        return this;
    }

    public Set<String> getTablenames() {
        return this.indexes.keySet();
    }

    /**
     * Add a non-hosted table
     * @param tablename
     * @param table
     * @return this
     */
    public PersistentTables addTable(final String tablename, final Table table) {
        final IndexedTable ti = new IndexedTable(table);
        this.indexes.put(tablename, ti);
        return this;
    }

    /**
     * Add a non-hosted table
     * @param tablename
     * @param table
     * @return this
     */
    public PersistentTables addTable(final String tablename, final IndexedTable table) {
        this.indexes.put(tablename, table);
        return this;
    }

    /**
     * Add a non-hosted table
     * @param tablename
     * @param table
     * @return this
     */
    public PersistentTables extendTable(final String tablename, final IndexedTable table) {
        final IndexedTable t = this.indexes.get(tablename);
        if (t == null) {
            this.indexes.put(tablename, table);
        } else {
            t.append(table);
            this.indexes.put(tablename, t);
        }
        return this;
    }

    public void storeTable(final String tablename) throws IOException {
        final IndexedTable t = this.indexes.get(tablename);
        if (t == null) return;
        if (this.io == null) throw new IOException("no io defined");
        if (this.iop == null) throw new IOException("no io path defined");
        final IOPath key = this.iop.append(tablename + ".json");
        try {
            this.io.write(key, t.toJSON(true).toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (final JSONException e) {
            throw new IOException(e.getMessage());
        }
    }


    /**
     * Retrieve named table from index
     * In case the index is hosted, the resulting table may be altered but it would not alter the hosted table.
     * If the table is not hosted, altering the table will alter the table for all other requests as well.
     * @param tablename
     * @return
     */
    public IndexedTable getTable(final String tablename) throws IOException {
        return where(tablename);
    }


    public static Table head(final Table table, final int count) {
        final Table t = table.emptyCopy();
        for (int r = 0; r < Math.min(count, table.rowCount()); r++) {
            t.addRow(table.row(r));
        }
        return t;
    }

    /**
     * Client to the persisten table which is able to switch between locally hosted and remote-hosted tables.
     * Where select statement on top layer. It is important to use this as entry point
     * for all hosted tables because in case that the tables are backed with another server
     * it forwards the select statement over the network instead of pulling a whole table
     * and performing the select then.
     * @param tablename
     * @param selects a list of strings where each string is a "key:value" pair - or one string with such pairs concatenated with ','
     * @return
     */
    public IndexedTable where(final String tablename, String... selects) throws IOException {
        if (selects.length == 1 && selects[0].contains(",")) selects = selects[0].split(",");

        // try: load from remote server
        if (this.urlstub != null) {
            try {
                final StringBuilder sb = new StringBuilder();
                for (final String u: selects) sb.append(u).append(',');
                final String url = this.urlstub + tablename + ".json" + ((selects.length == 0) ? "" : "?where=" + sb.substring(0, sb.length() - 1));
                Logger.info("loading: " + url);
                final Source source = Source.fromUrl(url);
                final Table t = new JsonReader().read(JsonReadOptions.builder(source).sample(false).build());
                return new IndexedTable(t);
            } catch (final IOException e) {
                Logger.debug(e.getMessage(), e);
            }
        }

        // try: load from local copy
        IndexedTable table = this.indexes.get(tablename);
        // in case the table is not inside the index, load it now
        if (table == null) {
            final IOPath key = this.iop.append(tablename + ".json");
            final byte[] b = this.io.readAll(key);
            try {
                final JSONArray a = new JSONArray(new JSONTokener(new String(b, StandardCharsets.UTF_8)));
                table = new IndexedTable(a);
            } catch (final JSONException e) {
                throw new IOException(e.getMessage());
            }
        }
        // process where statements
        if (selects.length == 0) return table;
        return table.whereSelects(selects);
    }


}
