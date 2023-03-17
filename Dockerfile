FROM tomcat:10

COPY target/nanopub-registry.war /usr/local/tomcat/webapps/ROOT.war
