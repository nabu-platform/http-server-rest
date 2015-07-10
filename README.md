# Description

This package provides a JAX-RS implementation on top of the http server. Most of the things are designed in line with the JAX-RS spec but it does diverge in some places, especially because of the custom HTTP design, some notable differences:

- The `Context` annotation does allow for example access to the security context, but it also allows the http server to set custom contexts which are resolved based on type
- You have access to different objects, e.g. HTTPRequest, Header[], Part,... by putting them in the argument list of your method

## How to use

The easiest way to register a JAX-RS annotated class:

```java
RoleHandler roleHandler = ...;
HTTPServer server = ...;
HTTPServerRESTUtils.handleRest(
	server, 
	MyRestClass.class, 
	"/", 
	roleHandler
);
```
