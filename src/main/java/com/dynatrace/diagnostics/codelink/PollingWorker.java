package com.dynatrace.diagnostics.codelink;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.jetbrains.annotations.Nullable;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

class PollingWorker implements Runnable {
    public static final int RESPONSE_FOUND = 50;
    public static final int RESPONSE_NOT_FOUND = 51;

    private static StringBuilder buildURL(ICodeLinkSettings settings) {
        return new StringBuilder(settings.isSSL() ? "https://" : "http://")
                .append(settings.getHost())
                .append(':').append(settings.getPort())
                .append("/rest/management/codelink/");
    }

    private final CloseableHttpClient client;
    private final ICodeLinkSettings clSettings;
    private final IIDEDescriptor ide;

    private long sessionId = -1;
    private boolean hasErrored = false;
    private int suppress = 0;

    public PollingWorker(IIDEDescriptor ide, ICodeLinkSettings clSettings) {
        this.ide = ide;
        this.clSettings = clSettings;
        this.client = Utils.createClient();
    }

    @Override
    public void run() {
        if (!this.clSettings.isEnabled()) {
            return;
        }

        // Not a perfect solution.
        if (this.suppress > 0) {
            this.suppress--;
            return;
        }

        try {
            CodeLinkLookupResponse response = this.connect();
            if (response == null) {
                return;
            }
            this.sessionId = response.sessionId;
            if (response.timedOut) {
                return;
            }
            long sid = this.sessionId;
            this.ide.jumpToClass(response.className, response.methodName, (b) -> {
                try {
                    this.respond(b ? RESPONSE_FOUND : RESPONSE_NOT_FOUND, sid);
                } catch (IOException e) {
                    CodeLinkClient.LOGGER.warning("Could not send response to CodeLink: " + e.getMessage());
                }
                return;
            });

            this.hasErrored = false;
        } catch (UnknownHostException e) {
            this.clSettings.setEnabled(false);
            this.ide.showNotification("CodeLink Error", "<b>Check your configuration</b>");
            CodeLinkClient.LOGGER.warning("Could not connect to CodeLink: "+e.getMessage());
        } catch (Exception e) {
            if (!hasErrored) {
                this.ide.showNotification("CodeLink Error", "Could not connect to client.<br><b>Check your configuration</b>");
            }
            this.hasErrored = true;
            //skip 5 connections
            this.suppress = 5;
            CodeLinkClient.LOGGER.warning("Could not connect to CodeLink: "+e.getMessage()+".");
        }
    }

    @Nullable
    private CodeLinkLookupResponse connect() throws IOException, JAXBException {
        List<NameValuePair> nvps = new ArrayList<>();
        nvps.add(new BasicNameValuePair("ideid", String.valueOf(this.ide.getId())));
        nvps.add(new BasicNameValuePair("ideversion", this.ide.getVersion()));
        nvps.add(new BasicNameValuePair("major", this.ide.getPluginVersion().major));
        nvps.add(new BasicNameValuePair("minor", this.ide.getPluginVersion().minor));
        nvps.add(new BasicNameValuePair("revision", this.ide.getPluginVersion().revision));
        nvps.add(new BasicNameValuePair("sessionid", String.valueOf(this.sessionId)));
        nvps.add(new BasicNameValuePair("activeproject", this.ide.getProjectName()));
        nvps.add(new BasicNameValuePair("projectpath", this.ide.getProjectPath()));

        StringBuilder builder = PollingWorker.buildURL(this.clSettings).append("connect");
        HttpPost post = new HttpPost(builder.toString());

        post.setEntity(new UrlEncodedFormEntity(nvps));
        try (CloseableHttpResponse response = this.client.execute(post)) {
            CodeLinkLookupResponse lookup = Utils.inputStreamToObject(response.getEntity().getContent(), CodeLinkLookupResponse.class);
            return lookup;
        }
    }

    private void respond(int responseCode, long sessionId) throws IOException {
        List<NameValuePair> nvps = new ArrayList<>();
        nvps.add(new BasicNameValuePair("sessionid", String.valueOf(sessionId)));
        nvps.add(new BasicNameValuePair("responsecode", String.valueOf(responseCode)));

        //TODO:                                        make it thread safe
        StringBuilder builder = PollingWorker.buildURL(this.clSettings).append("response");
        HttpPost post = new HttpPost(builder.toString());

        post.setEntity(new UrlEncodedFormEntity(nvps));
        try (CloseableHttpResponse response = this.client.execute(post)) {
            //??
        }
    }
}
