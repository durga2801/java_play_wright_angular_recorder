package com.example.angularrecorder.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ProjectRootResolver {

    private ProjectRootResolver() {}

    public static Path findCurrentMavenProjectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();

        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }

        throw new IllegalStateException(
                "Could not locate pom.xml from current working directory."
        );
    }
}
