package com.dropbox.core;

import com.dropbox.core.util.StringUtil;
import org.testng.annotations.Test;

import java.net.URL;
import java.util.*;

import static org.testng.Assert.*;

public class DbxWebAuthTest extends DbxOAuthTestBase {
    private static final DbxRequestConfig CONFIG = DbxRequestConfig.newBuilder("DbxWebAuthTest/1.0")
            .withUserLocaleFrom(Locale.UK)
            .build();
    private static final DbxAppInfo APP = new DbxAppInfo("test-key", "test-secret");

    @Test
    @SuppressWarnings("deprecation")
    public void testCompatibility() throws Exception {
        String redirectUri = "https://localhost/compatibility/test";
        DbxSessionStore sessionStore = new SimpleSessionStore();
        String state = "test-state";

        DbxWebAuth deprecated = new DbxWebAuth(CONFIG, APP, redirectUri, sessionStore);
        DbxWebAuth auth = new DbxWebAuth(CONFIG, APP);

        String deprecatedAuthUrl = deprecated.start(state);
        String authUrl = auth.authorize(
            DbxWebAuth.newRequestBuilder()
                .withRedirectUri(redirectUri, sessionStore)
                .withState(state)
                .build()
        );

        assertAuthorizationUrls(deprecatedAuthUrl, authUrl);

        deprecatedAuthUrl = deprecated.start(null);
        authUrl = auth.authorize(
            DbxWebAuth.newRequestBuilder()
                .withRedirectUri(redirectUri, sessionStore)
                .build()
        );

        // ignores state param since it includes a CSRF nonce
        assertAuthorizationUrls(deprecatedAuthUrl, authUrl);

        // assert token access type is empty
        Map<String, List<String>> params = toParamsMap(new URL(authUrl));
        assertFalse(params.containsKey("token_access_type"));
    }

    @Test(expectedExceptions={IllegalArgumentException.class})
    @SuppressWarnings("deprecation")
    public void testDeprecatedStateTooLarge() {
        StringBuilder state = new StringBuilder();
        for (int i = 0; i < 476; ++i) {
            state.append(".");
        }
        try {
            new DbxWebAuth(CONFIG, APP, "https://localhost", new SimpleSessionStore())
                .start(state.toString());
        } catch (IllegalArgumentException ex) {
            fail("Unable to create authorization URL with max state bytes.");
        }

        state.append("."); // one too many, should throw exception
        new DbxWebAuth(CONFIG, APP, "https://localhost", new SimpleSessionStore())
            .start(state.toString());
    }

    @Test(expectedExceptions={IllegalArgumentException.class})
    public void testStateTooLarge() {
        StringBuilder state = new StringBuilder();
        for (int i = 0; i < 476; ++i) {
            state.append(".");
        }
        try {
            DbxWebAuth.newRequestBuilder()
                .withRedirectUri("http://localhost/bar", new SimpleSessionStore())
                .withState(state.toString())
                .build();
        } catch (IllegalArgumentException ex) {
            fail("Unable to create OAuth request with max state bytes.");
        }

        state.append("."); // one too many, should throw exception
        DbxWebAuth.newRequestBuilder()
            .withRedirectUri("http://localhost/bar", new SimpleSessionStore())
            .withState(state.toString())
            .build();
    }

    @Test(expectedExceptions={IllegalStateException.class})
    @SuppressWarnings("deprecation")
    public void testDeprecatedBadStartCall() {
        new DbxWebAuth(CONFIG, APP).start("some-state");
    }

    @Test(expectedExceptions={IllegalStateException.class})
    @SuppressWarnings("deprecation")
    public void testBadStartCall() {
        new DbxWebAuth(CONFIG, APP, "http://localhost/banana", new SimpleSessionStore())
            .authorize(DbxWebAuth.newRequestBuilder().build());
    }

    @Test(expectedExceptions={IllegalStateException.class})
    public void testStateWithNoRedirect() {
        DbxWebAuth.newRequestBuilder()
            .withNoRedirect()
            .withState("foo-state")
            .build();
    }

    @Test
    public void testTokenAccessType() throws Exception {
        DbxWebAuth dbxWebAuth = new DbxWebAuth(CONFIG, APP);
        DbxWebAuth.Request request = DbxWebAuth.newRequestBuilder()
                .withNoRedirect()
                .withTokenAccessType(TokenAccessType.ONLINE)
                .build();
        String urlString = dbxWebAuth.authorize(request);
        Map<String, List<String>> params = toParamsMap(new URL(urlString));
        assertEquals(params.get("token_access_type"), Collections.singletonList("online"));

        request = DbxWebAuth.newRequestBuilder()
                .withNoRedirect()
                .withTokenAccessType(TokenAccessType.OFFLINE)
                .build();
        urlString = dbxWebAuth.authorize(request);
        params = toParamsMap(new URL(urlString));
        assertEquals(params.get("token_access_type"), Collections.singletonList("offline"));
    }

    @Test(expectedExceptions={DbxWebAuth.CsrfException.class})
    public void testCsrfVerifyException() throws Exception {
        DbxSessionStore sessionStore = new SimpleSessionStore();
        sessionStore.set(StringUtil.urlSafeBase64Encode(new byte[16]));

        new DbxWebAuth(CONFIG, APP).finishFromRedirect(
            "http://localhost/redirect",
            sessionStore,
            params("code", "test-code",
                   "state", "_no_csrf_available_or_bad_token_|test-state")
        );
    }

    @Test(expectedExceptions={IllegalStateException.class})
    public void testDbxWebAuthWithoutSecret() {
        DbxAppInfo appNoSecret = new DbxAppInfo("test-key");
        DbxWebAuth dbxWebAuth = new DbxWebAuth(CONFIG, appNoSecret);

        dbxWebAuth.authorize(
                DbxWebAuth.newRequestBuilder()
                        .build()
        );
    }

    @Test
    public void testScope() throws Exception {
        DbxWebAuth dbxWebAuth = new DbxWebAuth(CONFIG, APP);
        DbxWebAuth.Request request = DbxWebAuth.newRequestBuilder()
            .withNoRedirect()
            .withScope(Collections.singletonList("account.info.read"))
            .build();
        String urlString = dbxWebAuth.authorize(request);
        Map<String, List<String>> params = toParamsMap(new URL(urlString));
        assertEquals(params.get("scope"), Collections.singletonList("account.info.read"));
        assertNull(params.get("include_granted_scopes"));
    }

    @Test
    public void testIncrementalOAuth() throws Exception {
        DbxWebAuth dbxWebAuth = new DbxWebAuth(CONFIG, APP);
        DbxWebAuth.Request request = DbxWebAuth.newRequestBuilder()
            .withNoRedirect()
            .withScope(Collections.singletonList("account.info.read"))
            .withIncludeGrantedScopes(IncludeGrantedScopes.USER)
            .build();
        String urlString = dbxWebAuth.authorize(request);
        Map<String, List<String>> params = toParamsMap(new URL(urlString));
        assertEquals(params.get("client_id"), Collections.singletonList(APP.getKey()));
        assertEquals(params.get("response_type"), Collections.singletonList("code"));
        assertEquals(params.get("scope"), Collections.singletonList("account.info.read"));
        assertEquals(params.get("include_granted_scopes"), Collections.singletonList("user"));
        assertFalse(params.containsKey("redirect_uri"));
        assertFalse(params.containsKey("state"));
        assertFalse(params.containsKey("require_role"));
        assertFalse(params.containsKey("force_reapprove"));
        assertFalse(params.containsKey("disable_signup"));
        assertFalse(params.containsKey("token_access_type"));

    }
}
