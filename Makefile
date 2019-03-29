VERSION := 3.1.0.3-tet.3

clean:
	mvn clean
	rm -rf target/*

install:
	mvn clean install

publish:
	mvn -D VERSION=$(VERSION) -DaltDeploymentRepository=central::default::http://artifactory.tetrationanalytics.com:8081/artifactory/libs-snapshot-local deploy
