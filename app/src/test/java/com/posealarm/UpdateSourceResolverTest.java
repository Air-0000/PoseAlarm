package com.posealarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UpdateSourceResolverTest {

    private static final String GITEE = "https://gitee.com/Air-0000/posealarm-update/raw/master/version.json";
    private static final String GITHUB = "https://github.com/Air-0000/PoseAlarm/releases/latest/download/version.json";
    private static final String GITEE_APK = "https://gitee.com/Air-0000/posealarm-update/releases/download/v1.0.1/PoseAlarm_v1.0.1.apk";
    private static final String GITHUB_APK = "https://github.com/Air-0000/PoseAlarm/releases/download/v1.0.1/PoseAlarm_v1.0.1.apk";

    @Test
    public void sourcesPutCustomFirstAndSkipBlanks() {
        assertEquals(Arrays.asList(GITEE, GITHUB), UpdateSourceResolver.buildSources("", GITEE, GITHUB));
        assertEquals(Arrays.asList("http://192.168.1.5:8080/version.json", GITEE, GITHUB),
                UpdateSourceResolver.buildSources("http://192.168.1.5:8080/version.json", GITEE, GITHUB, GITEE));
    }

    @Test
    public void downloadTriesGiteeThenGithubThenSameHost() {
        List<String> c = UpdateSourceResolver.buildDownloadCandidates(GITHUB, GITEE_APK,
                Collections.singletonList(GITHUB_APK));
        assertEquals(Arrays.asList(GITEE_APK, GITHUB_APK,
                "https://github.com/Air-0000/PoseAlarm/releases/latest/download/PoseAlarm_v1.0.1.apk"), c);
    }

    @Test
    public void lanSourceDownloadsLocallyFirst() {
        String lan = "http://192.168.1.5:8080/posealarm/version.json";
        List<String> c = UpdateSourceResolver.buildDownloadCandidates(lan, GITEE_APK,
                Collections.singletonList(GITHUB_APK));
        assertEquals("http://192.168.1.5:8080/posealarm/PoseAlarm_v1.0.1.apk", c.get(0));
        assertEquals(GITEE_APK, c.get(1));
        assertEquals(GITHUB_APK, c.get(2));
    }

    @Test
    public void pickPrefersGiteeWithinGraceWindow() {
        List<String> sources = Arrays.asList(GITEE, GITHUB);
        int[] states = {UpdateSourceResolver.STATE_PENDING, UpdateSourceResolver.STATE_OK};
        // GitHub 先回应，但 Gitee 还在 4 秒窗口内：继续等
        assertEquals(UpdateSourceResolver.PICK_WAIT, UpdateSourceResolver.pickSource(sources, states, 1000));
        // 超过窗口：用 GitHub
        assertEquals(1, UpdateSourceResolver.pickSource(sources, states, 4500));
        states[0] = UpdateSourceResolver.STATE_OK;
        assertEquals(0, UpdateSourceResolver.pickSource(sources, states, 1000));
        int[] failed = {UpdateSourceResolver.STATE_FAILED, UpdateSourceResolver.STATE_FAILED};
        assertEquals(UpdateSourceResolver.PICK_NONE, UpdateSourceResolver.pickSource(sources, failed, 100));
    }
}
