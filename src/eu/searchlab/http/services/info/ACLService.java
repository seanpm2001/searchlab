/**
 *  ACLService
 *  Copyright 23.07.2022 by Michael Peter Christen, @orbiterlab
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

package eu.searchlab.http.services.info;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

import eu.searchlab.aaaaa.Authorization;
import eu.searchlab.http.AbstractService;
import eu.searchlab.http.Service;
import eu.searchlab.http.ServiceRequest;
import eu.searchlab.http.ServiceResponse;
import eu.searchlab.tools.Logger;

public class ACLService extends AbstractService implements Service {

    @Override
    public String[] getPaths() {
        return new String[] {"/access/services/", "/api/acl.json"};
    }

    @Override
    public ServiceResponse serve(final ServiceRequest serviceRequest) throws IOException {
        final JSONObject json = new JSONObject(true);
        try {
            json.put("acl", serviceRequest.getACL());
            json.put("aclall", Authorization.getACL());
        } catch (final JSONException e) {
            Logger.warn(e);
        }
        return new ServiceResponse(json);
    }

}
