package me.mrnavastar.ramjet.config;

import me.mrnavastar.ramjet.util.result.Fate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

public class GitConfig {

    private static final Path REPO_ROOT = Path.of("/config");

    public static void pollRepo(String repo, String branch, int pollRate, Runnable onChange) {
        Fate<Git> git = Fate.of(() -> Git.cloneRepository()
                .setURI(repo)
                .setBranch(branch)
                .setDirectory(REPO_ROOT.toFile())
                .call())
            .or(() -> Git.open(REPO_ROOT.toFile()));

        Thread.ofVirtual().start(() -> {
            try {
                RevCommit currentCommit = null;
                for (;;) {
                    git.resolve().pull().setStrategy(MergeStrategy.THEIRS).call();
                    var commit = git.resolve().log().call().iterator().next();
                    if (currentCommit == null || !currentCommit.getId().equals(commit.getId())) {
                        currentCommit = commit;
                        onChange.run();
                    }
                    Thread.sleep(pollRate * 1000L);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static Stream<File> listScripts(String dir) {
        return Arrays.stream(Objects.requireNonNull(new File(REPO_ROOT.resolve(dir).toUri()).listFiles()))
                .filter(file -> file.getName().endsWith(".lua"));
    }
}
