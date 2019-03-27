VERSION := 3.1.0.3-tet.3

clean:
	mvn clean
	rm -rf target/*

install:
	mvn clean install
