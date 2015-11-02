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
