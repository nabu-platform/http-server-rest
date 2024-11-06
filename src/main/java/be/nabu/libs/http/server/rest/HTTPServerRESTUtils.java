/*
* Copyright (C) 2015 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.http.server.rest;

import be.nabu.libs.authentication.api.RoleHandler;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.server.HTTPServerUtils;

public class HTTPServerRESTUtils {

	public static EventHandler<HTTPRequest, HTTPResponse> restHandler(Class<?> restClass, String serverPath, RoleHandler roleHandler, Object...context) {
		return new RESTHandler(serverPath, restClass, roleHandler, context);
	}
	
	public static void handleRest(HTTPServer server, Class<?> restClass, String serverPath, RoleHandler roleHandler, Object...context) {
		server.getDispatcher(null).subscribe(HTTPRequest.class, restHandler(restClass, serverPath, roleHandler, context))
			.filter(HTTPServerUtils.limitToPath(serverPath));
	}
}
