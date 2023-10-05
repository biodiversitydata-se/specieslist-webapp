# Specieslist-webapp

## Setup

### Config and data directory
Create data directory at `/data/specieslist-webapp` and populate as below (it is easiest to symlink the config files to the ones in this repo):
```
$ tree /data/specieslist-webapp
/data/specieslist-webapp
├── config
│   ├── logback.xml -> /home/manash/projects/specieslist-webapp/sbdi/data/config/logback.xml
│   ├── specieslist-webapp-config.properties -> /home/manash/projects/specieslist-webapp/sbdi/data/config/specieslist-webapp-config.properties
```

### Database
An empty database will be created the first time the application starts. You can then export the database from production and import it.

## Usage
Run locally:
```
make run
```

Build and run in Docker (using Tomcat). This requires a small change in the config file to work. See comment in Makefile.
```
make run-docker
```

Make a release. This will create a new tag and push it. A new Docker container will be built on Github.
```
$ make release
Current version: 1.0.1. Enter the new version (or press Enter for 1.0.2): 
Updating to version 1.0.2
Tag 1.0.2 created and pushed.
```
