FROM tomcat:11.0

COPY target/nanopub-registry.war /usr/local/tomcat/webapps/ROOT.war
