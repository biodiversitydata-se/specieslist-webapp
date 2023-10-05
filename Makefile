#! make
run:
	docker compose up --detach mysqldb-specieslist-webapp
	./gradlew bootRun

#In the specieslist-webapp-config.properties change the dataSource.url to point to the docker service mysqldb-specieslist as defined in the compose file
run-docker:
	./gradlew clean build
	docker compose build --no-cache
	docker compose up --detach

release:
	@./sbdi/make-release.sh
