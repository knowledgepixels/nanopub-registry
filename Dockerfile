FROM maven:3.9.11-eclipse-temurin-25

ENV APP_DIR /app
ENV TMP_DIR /tmp

WORKDIR $TMP_DIR

COPY pom.xml pom.xml

RUN mvn install

COPY src src

RUN mvn install -o && \
    mkdir $APP_DIR && \
    mv target/nanopub-registry-*-fat.jar $APP_DIR/nanopub-registry.jar && \
    rm -rf $TMP_DIR

WORKDIR $APP_DIR

EXPOSE 9292

ENTRYPOINT ["java","-jar","nanopub-registry.jar"]