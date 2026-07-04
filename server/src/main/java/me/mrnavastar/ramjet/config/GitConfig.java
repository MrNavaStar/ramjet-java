package me.mrnavastar.ramjet.config;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.merge.MergeStrategy;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

public class GitConfig {

    private static final Path REPO_ROOT = Path.of("./config");

    public static void pollRepo(String repo, String branch, int pollRate, Runnable onChange) {
        try(org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.cloneRepository()
                .setURI(repo)
                .setBranch(branch)
                .setDirectory(REPO_ROOT.toFile())
                .call()) {

            Thread.ofVirtual().start(() -> {
                try {
                    var currentCommit = git.log().call().iterator().next();

                    for (;;) {
                        Thread.sleep(pollRate * 1000L);
                        git.pull().setStrategy(MergeStrategy.THEIRS).call();

                        var commit = git.log().call().iterator().next();
                        if (currentCommit != commit) {
                            currentCommit = commit;
                            onChange.run();
                        }
                    }
                } catch (Exception _) {

                }
            });
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    public static Stream<String> listScripts(String dir) {
        return Arrays.stream(Objects.requireNonNull(new File(REPO_ROOT.resolve(dir).toUri()).list()))
                .filter(filename -> filename.endsWith(".lua"));
    }
}
