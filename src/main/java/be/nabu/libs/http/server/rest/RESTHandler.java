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

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import be.nabu.libs.authentication.api.RoleHandler;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.api.server.SecurityHeader;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.resources.URIUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeUtils;

public class RESTHandler implements EventHandler<HTTPRequest, HTTPResponse> {

	private Class<?> restClass;
	private String applicationPath, classPath;
	private List<Object> context;
	private List<Field> contextFields;
	private RoleHandler roleHandler;
	private Map<String, RESTMethod> methods = new HashMap<String, RESTMethod>();

	public RESTHandler(String applicationPath, Class<?> restClass, RoleHandler roleHandler, Object...context) {
		this.roleHandler = roleHandler;
		this.applicationPath = applicationPath == null ? "/" : applicationPath;
		this.restClass = restClass;
		analyze(restClass);
		this.context = Arrays.asList(context);
	}

	private void analyze(Class<?> clazz) {
		if (clazz.getAnnotation(Path.class) != null) {
			classPath = clazz.getAnnotation(Path.class).value();
		}
		for (Method method : clazz.getDeclaredMethods()) {
			methods.put(method.getName(), new RESTMethod(this, method));
		}
		contextFields = new ArrayList<Field>();
		for (Field field : clazz.getDeclaredFields()) {
			if (field.getAnnotation(Context.class) != null) {
				if (!field.isAccessible()) {
					field.setAccessible(true);
				}
				contextFields.add(field);
			}
		}
	}

	Object newInstance(final HTTPRequest request) throws IllegalArgumentException, IllegalAccessException, InstantiationException {
		Object instance = restClass.newInstance();
		for (Field field : contextFields) {
			if (HTTPRequest.class.equals(field.getType())) {
				field.set(instance, request);
			}
			else if (SecurityContext.class.equals(field.getType())) {
				field.set(instance, new SecurityContext() {
					@Override
					public String getAuthenticationScheme() {
						if (request.getContent() == null) {
							return null;
						}
						Header header = MimeUtils.getHeader(ServerHeader.NAME_AUTHENTICATION_SCHEME, request.getContent().getHeaders());
						return header == null ? null : header.getValue();
					}
					@Override
					public Principal getUserPrincipal() {
						AuthenticationHeader authenticationHeader = HTTPUtils.getAuthenticationHeader(request);
						return authenticationHeader == null ? null : authenticationHeader.getToken();
					}
					@Override
					public boolean isSecure() {
						SecurityHeader securityHeader = HTTPUtils.getSecurityHeader(request);
						return securityHeader != null && securityHeader.getSecurityContext() != null;
					}
					@Override
					public boolean isUserInRole(String role) {
						if (roleHandler == null) {
							return false;
						}
						AuthenticationHeader authenticationHeader = HTTPUtils.getAuthenticationHeader(request);
						return authenticationHeader != null && roleHandler.hasRole(authenticationHeader.getToken(), role);
					}
				});
			}
			else {
				for (Object object : context) {
					if (object != null && field.getType().isAssignableFrom(object.getClass())) {
						field.set(instance, object);
						break;
					}
				}
			}
		}
		return instance;
	}
	
	public Class<?> getRestClass() {
		return restClass;
	}

	public String getApplicationPath() {
		return applicationPath;
	}

	public String getClassPath() {
		return classPath;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		// can not (currently) handle requests that have no content
		if (request.getContent() == null) {
			return null;
		}
		try {
			URI uri = URIUtils.normalize(HTTPUtils.getURI(request, false));
			if (uri.getPath().startsWith(getApplicationPath())) {
				String classPath = getClassPath();
				if (classPath == null || classPath.equals("/")) {
					classPath = getApplicationPath();
				}
				else {
					classPath = getApplicationPath().equals("/") ? classPath : getApplicationPath() + classPath;
				}
				if (uri.getPath().startsWith(classPath)) {
					// remove trailing / from classpath before we substring it so we retain the leading "/" in the path
					String path = uri.getPath().substring(classPath.replaceAll("[/]+$", "").length());
					if (path.isEmpty()) {
						path = "/";
					}
					Class<? extends Annotation> annotation = null;
					if (request.getMethod().equalsIgnoreCase("GET")) {
						annotation = GET.class;
					}
					else if (request.getMethod().equalsIgnoreCase("POST")) {
						annotation = POST.class;
					}
					else if (request.getMethod().equalsIgnoreCase("PUT")) {
						annotation = PUT.class;
					}
					else if (request.getMethod().equalsIgnoreCase("DELETE")) {
						annotation = DELETE.class;
					}
					else if (request.getMethod().equalsIgnoreCase("HEAD")) {
						annotation = HEAD.class;
					}
					else if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
						annotation = OPTIONS.class;
					}
					else {
						throw new HTTPException(500, "The method " + request.getMethod() + " is currently unsupported");
					}
					for (RESTMethod method : methods.values()) {
						if (method.isMethod(path, annotation)) {
							try {
								return method.execute(new URI(uri.getScheme(), uri.getAuthority(), path, uri.getQuery(), uri.getFragment()), request);
							}
							catch (HTTPException e) {
								throw e;
							}
							catch (Exception e) {
								Exception unwind = e;
								while (unwind instanceof InvocationTargetException && unwind.getCause() instanceof Exception) {
									if (unwind.getCause() instanceof HTTPException) {
										throw (HTTPException) unwind.getCause();
									}
									else {
										unwind = (Exception) unwind.getCause();
									}
								}
								throw new HTTPException(500, unwind);
							}
						}
					}
				}
			}
			return null;
		}
		catch (FormatException e) {
			throw new HTTPException(500, e);
		}
	}
}
