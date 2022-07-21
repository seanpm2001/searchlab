/**
 *  ServiceResponse
 *  Copyright 16.01.2017 by Michael Peter Christen, @orbiterlab
 *  (copied from yacy_grid_mcp over to searchlab and extended 02.06.2022)
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

package eu.searchlab.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import eu.searchlab.http.Service.Type;
import eu.searchlab.storage.table.IndexedTable;
import eu.searchlab.tools.Logger;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

public class ServiceResponse {

    private Object object;
    private Type type;
    private boolean setCORS;
    private final Set<Cookie> cookies;
    private int statusCode;
    private final Map<String, String> xtraHeaders;

    private ServiceResponse() {
        this.setCORS = false;
        this.cookies = new HashSet<>();
        this.statusCode = StatusCodes.OK;
        this.xtraHeaders = new LinkedHashMap<>();
    }

    public ServiceResponse(final JSONObject json) {
        this();
        this.object = json;
        this.type = Type.OBJECT;
    }

    public ServiceResponse(final JSONArray json) {
        this();
        this.object = json;
        this.type = Type.ARRAY;
    }

    public ServiceResponse(final String string) {
        this();
        this.object = string;
        this.type = Type.STRING;
    }

    public ServiceResponse(final byte[] bytes) {
        this();
        this.object = bytes;
        this.type = Type.BINARY;
    }

    public ServiceResponse(final IndexedTable table) {
        this();
        this.object = table;
        this.type = Type.TABLE;
    }

    public int getStatusCode() {
        return this.statusCode;
    }

    public Map<String, String> getXtraHeaders() {
        return this.xtraHeaders;
    }

    public ServiceResponse setValue(final JSONObject json) {
        this.object = json;
        this.type = Type.OBJECT;
        return this;
    }

    public ServiceResponse setValue(final JSONArray json) {
        this.object = json;
        this.type = Type.ARRAY;
        return this;
    }

    public ServiceResponse setValue(final String value) {
        this.object = value;
        this.type = Type.STRING;
        return this;
    }

    public ServiceResponse setValue(final byte[] value) {
        this.object = value;
        this.type = Type.BINARY;
        return this;
    }

    public ServiceResponse setValue(final IndexedTable table) {
        this.object = table;
        this.type = Type.TABLE;
        return this;
    }

    public ServiceResponse setCORS() {
        this.setCORS = true;
        return this;
    }

    /**
     * Attach a session cookie.
     * These cookies are temporary and expire once you close your browser (or once your session ends).
     * GDPR Requirement: Servers must receive users’ consent before you use any cookies except strictly necessary cookies.
     * See https://gdpr.eu/cookies/
     * Session cookies are identified by the browser by the absence of an expiration date assigned to them.
     * See https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie#session_cookie
     * @param name
     * @param value
     * @return
     */
    public ServiceResponse addSessionCookie(final String name, final String value) {
        final CookieImpl cookie = new CookieImpl(name).setValue(value);
        cookie.setPath("/"); // required to make the cookie valid for all requests to the same host
        cookie.setSameSiteMode("Lax"); // the cookie is set when navigating from outside the domain to the domain, but not cross-site requests
        cookie.setHttpOnly(true); // prevents access by Javascript, some protection against cross-site request forgery attacks
        // DO NOT SET Expires, DO NOT SET Max-Age
        this.cookies.add(cookie);
        return this;
    }

    public ServiceResponse deleteCookie(final String name) {
        final CookieImpl cookie = new CookieImpl(name).setValue("");
        cookie.setPath("/"); // required to make the cookie valid for all requests to the same host
        cookie.setSameSiteMode("Lax"); // the cookie is set when navigating from outside the domain to the domain, but not cross-site requests
        cookie.setHttpOnly(true); // prevents access by Javascript, some protection against cross-site request forgery attacks
        cookie.setMaxAge(-1);
        cookie.setExpires(new Date(0));
        this.cookies.add(cookie);
        return this;
    }

    public ServiceResponse addUserIDCookie(final String value) {
        return addSessionCookie(WebServer.COOKIE_USER_ID_NAME, value);
    }

    public ServiceResponse deleteUserIDCookie() {
        return deleteCookie(WebServer.COOKIE_USER_ID_NAME);
    }

    public Set<Cookie> getCookies() {
        return this.cookies;
    }

    public ServiceResponse setFoundRedirect(final String url) {
        // used for oauth, see https://datatracker.ietf.org/doc/html/rfc7231#section-6.4.3
        this.statusCode = StatusCodes.FOUND;
        this.xtraHeaders.put(Headers.LOCATION_STRING, url);
        return this;
    }

    public ServiceResponse setTooManyRequests(final long retryAfter) {
        // used for oauth, see https://datatracker.ietf.org/doc/html/rfc7231#section-6.4.3
        this.statusCode = StatusCodes.TOO_MANY_REQUESTS;
        this.xtraHeaders.put(Headers.RETRY_AFTER_STRING, Long.toString(retryAfter));
        return this;
    }

    public Type getType() {
        return this.type;
    }

    public boolean allowCORS() {
        return this.setCORS;
    }

    public boolean isObject() {
        return this.object instanceof JSONObject;
    }

    public boolean isArray() {
        return this.object instanceof JSONArray;
    }

    public boolean isString() {
        return this.object instanceof String;
    }

    public boolean isByteArray() {
        return this.object instanceof byte[];
    }

    public boolean isTable() {
        return this.object instanceof IndexedTable;
    }

    public String getMimeType() {
        if (isObject() || isArray()) return "application/javascript";
        if (isString()) {
            try {
                return getString().startsWith("<?xml") ? "application/xml" : "text/plain";
            } catch (final IOException e) {
                return "application/octet-stream";
            }
        }
        return "application/octet-stream";
    }

    public JSONObject getObject() throws IOException {
        if (!isObject()) throw new IOException("object type is not JSONObject: " + this.object.getClass().getName());
        return (JSONObject) this.object;
    }

    public JSONArray getArray() throws IOException {
        if (!isArray()) throw new IOException("object type is not JSONArray: " + this.object.getClass().getName());
        return (JSONArray) this.object;
    }

    public String getString() throws IOException {
        if (!isString()) throw new IOException("object type is not String: " + this.object.getClass().getName());
        return (String) this.object;
    }

    public byte[] getByteArray() throws IOException {
        if (!isByteArray()) throw new IOException("object type is not ByteArray: " + this.object.getClass().getName());
        return (byte[]) this.object;
    }

    public IndexedTable getTable() throws IOException {
        if (!isTable()) throw new IOException("object type is not Table: " + this.object.getClass().getName());
        return (IndexedTable) this.object;
    }

    public String toString(final boolean minified) throws IOException {
        try {
            if (isObject()) return getObject().toString(minified ? 0 : 2);
            if (isArray()) return getArray().toString(minified ? 0 : 2);
            if (isString()) return getString();
            if (isByteArray()) return new String((byte[]) this.object, StandardCharsets.UTF_8);
        } catch (final JSONException e) {
            Logger.error(e);
        }
        return null;
    }

    public byte[] toByteArray(final boolean minified) throws IOException {
        try {
            if (isObject()) return getObject().toString(minified ? 0 : 2).getBytes(StandardCharsets.UTF_8);
            if (isArray()) return getArray().toString(minified ? 0 : 2).getBytes(StandardCharsets.UTF_8);
            if (isString()) return getString().getBytes(StandardCharsets.UTF_8);
            if (isByteArray()) return (byte[]) this.object;
        } catch (final JSONException e) {
            Logger.error(e);
        }
        return null;
    }
}
