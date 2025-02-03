# Use an official Java runtime as a parent image
FROM openjdk:11

# Set the working directory in the container to /app
WORKDIR /app

# Copy the current directory contents into the container at /app
COPY . /app

# Download and set up SQLite JDBC
#RUN wget https://bitbucket.org/xerial/sqlite-jdbc/downloads/sqlite-jdbc-3.34.0.jar -P /usr/local/bin
ENV CLASSPATH /sqlite-jdbc-3.34.0.jar:.

# Compile the server Java files
RUN javac -d bin/ -cp src/ --module-path /usr/local/bin/sqlite-jdbc-3.34.0.jar src/server/*.java src/Utilities/*.java


EXPOSE 50000
EXPOSE 50001
EXPOSE 50002
EXPOSE 50003
EXPOSE 50004
EXPOSE 50005


# Run the server when the container launches
CMD ["sh", "-c", "java -cp bin/ --module-path /usr/local/bin/sqlite-jdbc-3.34.0.jar server.Server"]
