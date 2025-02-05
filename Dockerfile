FROM tomcat:11.0

COPY conf/server.xml /usr/local/tomcat/conf/server.xml
COPY target/nanopub-registry.war /usr/local/tomcat/webapps/ROOT.war
