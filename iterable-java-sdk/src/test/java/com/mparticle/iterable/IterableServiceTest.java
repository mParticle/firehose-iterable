package com.mparticle.iterable;

import org.junit.Before;
import org.mockito.Mockito;
import retrofit.Call;
import retrofit.Response;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * This is an *integration* test, testing:
 * 1. Model serialization/deserialization
 * 2. Iterable service authentication
 * <p>
 * If there's no API key specified in setup, these tests aren't particularly useful - nearly everything will be mocked.
 */
public class IterableServiceTest {

    private static final String TEST_EMAIL = "testing@mparticle.com";
    private static final String TEST_USER_ID = "123456";

    private IterableService iterableService;

    @Before
    public void setUp() throws Exception {
        String iterableApiKey = ""; //put your API key here

        if (iterableApiKey == null || iterableApiKey.length() == 0) {
            iterableService = Mockito.mock(IterableService.class);

            Call callMock = Mockito.mock(Call.class);
            Mockito.when(iterableService.userUpdate(Mockito.any()))
                    .thenReturn(callMock);
            Mockito.when(iterableService.track(Mockito.any()))
                    .thenReturn(callMock);
            Mockito.when(iterableService.registerToken(Mockito.any()))
                    .thenReturn(callMock);
            Mockito.when(iterableService.trackPushOpen(Mockito.any()))
                    .thenReturn(callMock);
            IterableApiResponse apiResponse = new IterableApiResponse();
            apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
            Response<IterableApiResponse> response = Response.success(apiResponse);
            Mockito.when(callMock.execute()).thenReturn(response);

            Call listCallMock = Mockito.mock(Call.class);
            ListResponse successlistResponse = new ListResponse();
            successlistResponse.successCount = 1;
            Response<ListResponse> listResponse = Response.success(successlistResponse);
            Mockito.when(listCallMock.execute()).thenReturn(listResponse);
            Mockito.when(iterableService.listSubscribe(Mockito.any()))
                    .thenReturn(listCallMock);
            Mockito.when(iterableService.listUnsubscribe(Mockito.any()))
                    .thenReturn(listCallMock);
        } else {
            iterableService = IterableService.newInstance(iterableApiKey);
        }
    }

    @org.junit.Test
    public void testTrack() throws Exception {
        TrackRequest trackRequest = new TrackRequest("Test Event");
        trackRequest.email = TEST_EMAIL;
        trackRequest.userId = TEST_USER_ID;
        Response<IterableApiResponse> response = iterableService.track(trackRequest).execute();
        assertTrue("Retrofit request not successful:\nMessage: " + response.message() + "\nCode: " + response.code(), response.isSuccess());
        assertTrue("Iterable response was not successful:\n" + response.body().toString(), response.body().isSuccess());
    }

    @org.junit.Test
    public void testTrackPushOpen() throws Exception {
        TrackPushOpenRequest pushOpenRequest = new TrackPushOpenRequest();
        pushOpenRequest.email = TEST_EMAIL;
        pushOpenRequest.userId = TEST_USER_ID;
        pushOpenRequest.campaignId = 17703; //this correlates to the "Test Campaign" set up in Iterable
        Map<String, String> attributes = new HashMap<String, String>();
        attributes.put("test push open attribute key", "test push open attribute value");
        pushOpenRequest.dataFields = attributes;
        Response<IterableApiResponse> response = iterableService.trackPushOpen(pushOpenRequest).execute();
        assertTrue("Retrofit request not successful:\nMessage: " + response.message() + "\nCode: " + response.code(), response.isSuccess());
        assertTrue("Iterable response was not successful:\n" + response.body().toString(), response.body().isSuccess());
    }

    @org.junit.Test
    public void testUserUpdate() throws Exception {
        UserUpdateRequest userUpdateRequest = new UserUpdateRequest();
        userUpdateRequest.userId = TEST_USER_ID;
        userUpdateRequest.email = TEST_EMAIL;
        Map<String, String> attributes = new HashMap<String, String>();
        attributes.put("test attribute key", "test attribute value");
        userUpdateRequest.dataFields = attributes;
        Response<IterableApiResponse> response = iterableService.userUpdate(userUpdateRequest).execute();
        assertTrue("Retrofit request not successful:\nMessage: " + response.message() + "\nCode: " + response.code(), response.isSuccess());
        assertTrue("Iterable response was not successful:\n" + response.body().toString(), response.body().isSuccess());
    }

    @org.junit.Test
    public void testRegisterToken() throws Exception {
        RegisterDeviceTokenRequest registerRequest = new RegisterDeviceTokenRequest();
        registerRequest.email = TEST_EMAIL;
        registerRequest.device = new Device();
        registerRequest.device.platform = Device.PLATFORM_GCM;
        registerRequest.device.applicationName = "mparticletestintegration";
        registerRequest.device.token = "thisisatestGCMtoken";
        Response<IterableApiResponse> response = iterableService.registerToken(registerRequest).execute();
        assertTrue("Retrofit request not successful:\nMessage: " + response.message() + "\nCode: " + response.code(), response.isSuccess());
        assertTrue("Iterable response was not successful:\n" + response.body().toString(), response.body().isSuccess());
    }

    @org.junit.Test
    public void testListSubscribe() throws Exception {
        SubscribeRequest subscribeRequest = new SubscribeRequest();
        subscribeRequest.listId = 7110;
        subscribeRequest.subscribers = new LinkedList<>();

        String userId1 = System.currentTimeMillis() + "";
        String userId2 = System.currentTimeMillis() + 10 + "";
        String email1 = userId1 + "@mparticle.com";
        String email2 = userId2 + "@mparticle.com";
        ApiUser user1 = new ApiUser();
        Map<String, String> attributes1 = new HashMap<String, String>();
        attributes1.put("test subscribe key", "test subscribe value 1");
        user1.dataFields = attributes1;
        user1.email = email1;
        user1.userId = userId1;
        Map<String, String> attributes2 = new HashMap<String, String>();
        attributes2.put("test subscribe key", "test subscribe value 2");
        ApiUser user2 = new ApiUser();
        user2.dataFields = attributes2;
        user2.email = email2;
        user2.userId = userId2;

        subscribeRequest.subscribers.add(user1);
        subscribeRequest.subscribers.add(user2);
        Response<ListResponse> response = iterableService.listSubscribe(subscribeRequest).execute();
        assertTrue("Retrofit request not successful:\nMessage: " + response.message() + "\nCode: " + response.code(), response.isSuccess());
        assertTrue("Iterable response was not successful:\nSuccess Count: " + response.body().successCount + "\nFail Count: " + response.body().failCount, response.body().failCount < 1);
    }

    @org.junit.Test
    public void testListUnsubscribe() throws Exception {
        UnsubscribeRequest unsubscribeRequest = new UnsubscribeRequest();
        unsubscribeRequest.listId = 7110;
        Unsubscriber user = new Unsubscriber();
        user.email = "newtestsubscriber@mparticle.com";
        unsubscribeRequest.subscribers = new LinkedList<>();
        unsubscribeRequest.subscribers.add(user);
        Response<ListResponse> response = iterableService.listUnsubscribe(unsubscribeRequest).execute();
        assertTrue("Retrofit request not successful:\nMessage: " + response.message() + "\nCode: " + response.code(), response.isSuccess());
        //just check for 200 since it's not feasible in an automated test to wait for Iterable to update its lists such an an unsubscribe will always work.
        // assertTrue("Iterable response was not successful:\nSuccess Count: " + response.body().successCount + "\nFail Count: " + response.body().failCount, response.body().failCount < 1);
    }
}