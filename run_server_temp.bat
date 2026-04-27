@echo off
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
set "MAVEN_HOME=C:\Program Files\apache-maven-3.9.14"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
mvn -DskipTests exec:java -Dexec.mainClass="com.multiplayer.server.network.GameServer"
