FROM tomcat:9

COPY target/nanopub-registry.war /usr/local/tomcat/webapps/ROOT.war
