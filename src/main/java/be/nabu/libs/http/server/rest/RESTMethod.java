package be.nabu.libs.http.server.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.converter.ConverterFactory;
import be.nabu.libs.converter.api.Converter;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedSimpleType;
import be.nabu.libs.types.api.Marshallable;
import be.nabu.libs.types.api.SimpleTypeWrapper;
import be.nabu.libs.types.binding.api.MarshallableBinding;
import be.nabu.libs.types.binding.api.UnmarshallableBinding;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.json.JSONBinding;
import be.nabu.libs.types.binding.xml.XMLBinding;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.libs.types.java.BeanResolver;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.api.Part;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.ParsedMimeFormPart;

public class RESTMethod {

	private Method method;
	
	private Map<Class<? extends Annotation>, Boolean> isMethod = new HashMap<Class<? extends Annotation>, Boolean>();
	private List<Object> methodParameters;
	private String methodPath;
	private String [] produces, consumes;
	private List<String> pathParameters;
	private RESTHandler restHandler;
	private SimpleTypeWrapper simpleTypeWrapper = SimpleTypeWrapperFactory.getInstance().getWrapper();
	private Converter converter = ConverterFactory.getInstance().getConverter();
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	RESTMethod(RESTHandler restHandler, Method method) {
		this.restHandler = restHandler;
		this.method = method;
		analyze(method);
		analyzeMethodParameters();
		analyzePathParameters();
	}
	
	private void analyze(Method method) {
		methodPath = method.getAnnotation(Path.class) != null ? method.getAnnotation(Path.class).value() : "/";
		produces = method.getAnnotation(Produces.class) != null ? method.getAnnotation(Produces.class).value() : null;
		consumes = method.getAnnotation(Consumes.class) != null ? method.getAnnotation(Consumes.class).value() : null;
		// allow class-level declaration as well
		if (produces == null && method.getDeclaringClass().getAnnotation(Produces.class) != null) {
			produces = method.getDeclaringClass().getAnnotation(Produces.class).value();
		}
		if (consumes == null && method.getDeclaringClass().getAnnotation(Consumes.class) != null) {
			consumes = method.getDeclaringClass().getAnnotation(Consumes.class).value();
		}
	}

	public boolean isMethod(String path, Class<? extends Annotation> annotation) {
		if (!isMethod.containsKey(annotation)) {
			isMethod.put(annotation, method.getAnnotation(annotation) != null);
		}
		if (!isMethod.get(annotation)) {
			return false;
		}
		// the syntax for a more controlled match is "{name:.+}" for example which will match anything
		String regex = methodPath.replaceAll("\\{[^/}:\\s]+[\\s]*:[\\s]*([^}\\s]+)[\\s]*\\}", "$1").replaceAll("\\{[^}/]+\\}", "[^/]+");
		return path.matches(regex);
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	HTTPResponse execute(URI uri, HTTPRequest request) throws ParseException, URISyntaxException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException, IOException {
		logger.debug("Executing method " + method.getName() + " in " + method.getDeclaringClass().getName());
		// @PathParam (from actual path, not query)
		// @FormParam (can be inputstream?)
		// @MatrixParam (ignore)
		// @QueryParam (from query parameters)
		String contentType = MimeUtils.getContentType(request.getContent().getHeaders());
		Map<String, String> pathValues = getPathValues(uri.getPath());
		Map<String, List<String>> queryValues = URIUtils.getQueryProperties(uri);
		Map<String, List<String>> formValues = null;
		if (MediaType.MULTIPART_FORM_DATA.equalsIgnoreCase(contentType)) {
			throw new HTTPException(500, "Multipart forms are currently not supported");
		}
		// if it is a form, it should've been parsed correctly, should really make an interface for this
		else if ("application/x-www-form-urlencoded".equalsIgnoreCase(contentType)) {
			if (!(request instanceof ParsedMimeFormPart)) {
				throw new HTTPException(500, "The form request was not correctly parsed");
			}
			formValues = ((ParsedMimeFormPart) request).getValues();
		}
		List<Object> arguments = new ArrayList<Object>();
		for (Object parameter : getMethodParameters()) {
			logger.trace("Finding parameter {}", parameter);
			if (parameter instanceof FormParam) {
				if (formValues == null) {
					throw new HTTPException(400, "No form parameters available");
				}
				List<String> list  = formValues.get(((FormParam) parameter).value());
				arguments.add(list == null || list.isEmpty() ? null : list.get(0));
			}
			else if (parameter instanceof QueryParam) {
				List<String> list = queryValues == null ? null : queryValues.get(((QueryParam) parameter).value());
				arguments.add(list == null || list.isEmpty() ? null : list.get(0));
			}
			else if (parameter instanceof HeaderParam) {
				Header header  = MimeUtils.getHeader(((HeaderParam) parameter).value(), request.getContent().getHeaders());
				arguments.add(header == null ? null : header.getValue());
			}
			else if (parameter instanceof PathParam) {
				arguments.add(pathValues.get(((PathParam) parameter).value()));
			}
			else if (parameter instanceof Class && InputStream.class.equals((Class) parameter)) {
				arguments.add(request.getContent() instanceof ContentPart ? IOUtils.toInputStream(((ContentPart) request.getContent()).getReadable()) : null);
			}
			else if (parameter instanceof Class && byte[].class.isAssignableFrom((Class) parameter)) {
				arguments.add(request.getContent() instanceof ContentPart ? IOUtils.toBytes(((ContentPart) request.getContent()).getReadable()) : null);
			}
			else if (parameter instanceof Class && (HTTPRequest.class.equals(parameter) || request.getClass().equals(parameter))) {
				arguments.add(request);
			}
			else if (parameter instanceof Class && Header[].class.equals(parameter)) {
				arguments.add(request.getContent().getHeaders());
			}
			else if (parameter instanceof Class && (Part.class.equals(parameter) || request.getContent().getClass().equals(parameter))) {
				arguments.add(request.getContent());
			}
			// we assume it's an interpreted object that is in the body
			else if (parameter instanceof Class) {
				Object unmarshalled = null;
				if (request instanceof ContentPart) {
					List<String> allowedContentTypes = consumes == null ? Arrays.asList(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON) : Arrays.asList(consumes);
					if (contentType != null && !allowedContentTypes.contains(contentType)) {
						throw new HTTPException(400, "Invalid content type");
					}
					UnmarshallableBinding binding = MediaType.APPLICATION_JSON.equals(contentType == null ? allowedContentTypes.get(0) : contentType) 
						? new JSONBinding((ComplexType) BeanResolver.getInstance().resolve((Class<?>) parameter))
						: new XMLBinding((ComplexType) BeanResolver.getInstance().resolve((Class<?>) parameter), Charset.defaultCharset());
					unmarshalled = binding.unmarshal(IOUtils.toInputStream(((ContentPart) request).getReadable()), new Window[0]);
				}
				arguments.add(unmarshalled);
			}
			else {
				throw new HTTPException(500, "Invalid parameter: " + parameter);
			}
		}
		logger.trace("Arguments: {}", arguments);
		// do some conversion if necessary
		Class<?>[] types = method.getParameterTypes();
		for (int i = 0; i < arguments.size(); i++) {
			if (arguments.get(i) != null && !types[i].isAssignableFrom(arguments.get(i).getClass())) {
				arguments.set(i, converter.convert(arguments.get(i), types[i]));
			}
		}
		Object response = method.invoke(restHandler.newInstance(request), arguments.toArray());
		if (response instanceof byte[]) {
			return HTTPUtils.newResponse(request, produces == null ? MediaType.APPLICATION_OCTET_STREAM : produces[0], IOUtils.wrap((byte[]) response, true));
		}
		else if (response instanceof InputStream) {
			return HTTPUtils.newResponse(request, produces == null ? MediaType.APPLICATION_OCTET_STREAM : produces[0], IOUtils.wrap((InputStream) response));
		}
		else if (response instanceof String) {
			return HTTPUtils.newResponse(request, produces == null ? MediaType.TEXT_PLAIN : produces[0], IOUtils.wrap(((String) response).getBytes(), true));
		}
		// if it's already a part, you did all the heavy lifting
		else if (response instanceof Part) {
			return new DefaultHTTPResponse(request, 200, "OK", MimeUtils.wrapModifiable((Part) response));
		}
		else if (response instanceof HTTPResponse) {
			return (HTTPResponse) response;
		}
		else if (response != null) {
			byte [] content;
			String responseType;
			
			if (response instanceof ComplexContent || simpleTypeWrapper.wrap(response.getClass()) == null) {
				List<String> allowedResponseTypes = produces == null ? Arrays.asList(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON) : Arrays.asList(produces);
				Header acceptHeader = MimeUtils.getHeader("Accept", request.getContent().getHeaders());
				responseType = acceptHeader != null && allowedResponseTypes.contains(acceptHeader.getValue()) ? acceptHeader.getValue() : allowedResponseTypes.get(0);
				MarshallableBinding binding = MediaType.APPLICATION_JSON.equals(responseType) 
					? new JSONBinding((ComplexType) (response instanceof ComplexContent ? ((ComplexContent) response).getType() : BeanResolver.getInstance().resolve(response.getClass())))
					: new XMLBinding((ComplexType) (response instanceof ComplexContent ? ((ComplexContent) response).getType() : BeanResolver.getInstance().resolve(response.getClass())), Charset.defaultCharset());
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				binding.marshal(output, response instanceof ComplexContent ? (ComplexContent) response : new BeanInstance(response));
				content = output.toByteArray();
			}
			else {
				DefinedSimpleType<? extends Object> resolved = simpleTypeWrapper.wrap(response.getClass());
				if (!(resolved instanceof Marshallable)) {
					throw new ParseException("The response of " + method + " is not marshallable", 0);
				}
				content = ((Marshallable) resolved).marshal(response).getBytes();
				responseType = "text/plain";
			}
			return HTTPUtils.newResponse(request, responseType, IOUtils.wrap(content, true));
		}
		else {
			return HTTPUtils.newEmptyResponse(request);
		}
	}
	
	private List<String> getPathParameters() {
		if (pathParameters == null) {
			synchronized(this) {
				if (pathParameters == null) {
					analyzePathParameters();
				}
			}
		}
		return pathParameters;
	}

	private void analyzePathParameters() {
		pathParameters = new ArrayList<String>();
		Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
		Matcher matcher = pattern.matcher(methodPath);
		while (matcher.find()) {
			pathParameters.add(matcher.group(1).replaceAll("[\\s]*:.*$", ""));
		}
	}
	
	private Map<String, String> getPathValues(String path) {
		List<String> pathParameters = getPathParameters();
		Map<String, String> values = new HashMap<String, String>();
		String regex = methodPath.replaceAll("\\{[^/}:\\s]+[\\s]*:[\\s]*([^}\\s]+)[\\s]*\\}", "($1)").replaceAll("\\{[^}/]+\\}", "([^/]+)");
		for (int i = 0; i < pathParameters.size(); i++) {
			String value = path.replaceAll(regex, "$" + (i + 1));
			values.put(pathParameters.get(i), value);
		}
		return values;
	}
	
	private List<Object> getMethodParameters() {
		if (methodParameters == null) {
			synchronized(this) {
				if (methodParameters == null) {
					analyzeMethodParameters();
				}
			}
		}
		return methodParameters;
	}

	private void analyzeMethodParameters() {
		// assign to local variable first, otherwise it's possible in massive concurrency to pick up an empty list
		List<Object> methodParameters = new ArrayList<Object>();
		Class<?>[] parameters = method.getParameterTypes();
		Annotation[][] parameterAnnotations = method.getParameterAnnotations();
		for (int i = 0; i < parameters.length; i++) {
			Annotation[] annotations = parameterAnnotations[i];
			boolean foundAnnotation = false;
			if (annotations != null) {
				for (Annotation annotation : annotations) {
					if (annotation instanceof PathParam || annotation instanceof QueryParam || annotation instanceof FormParam || annotation instanceof HeaderParam) {
						methodParameters.add(annotation);
						foundAnnotation = true;
						break;
					}
				}
			}
			// just add the class, we'll figure it out at runtime
			if (!foundAnnotation) {
				methodParameters.add(parameters[i]);
			}
		}
		this.methodParameters = methodParameters;
	}
	
	@Override
	public String toString() {
		return method.getDeclaringClass() + ":" + method.getName();
	}
}
