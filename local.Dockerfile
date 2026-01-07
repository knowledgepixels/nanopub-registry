FROM eclipse-temurin:25-jre AS build

ENV APP_DIR /app

WORKDIR $APP_DIR

COPY target target

RUN mv target/nanopub-registry-*-fat.jar nanopub-registry.jar

EXPOSE 9292

ENTRYPOINT ["java","-jar","nanopub-registry.jar"]
