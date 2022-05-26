/**
 *  ReadyService
 *  Copyright 26.05.2022 by Michael Peter Christen, @orbiterlab
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

package eu.searchlab.http.services;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

import eu.searchlab.Searchlab;
import eu.searchlab.http.Service;

public class ReadyService extends AbstractService implements Service {

 @Override
 public String[] getPaths() {
     return new String[] {"/api/ready.json"};
 }

 @Override
 public JSONObject serveObject(final JSONObject post) throws IOException {
     if (!Searchlab.ready) throw new IOException("not ready");
     final JSONObject json = new JSONObject();
     try {
        json.put("ready", true);
    } catch (final JSONException e) {
        throw new IOException(e.getMessage());
    }
     return json;
 }

}
