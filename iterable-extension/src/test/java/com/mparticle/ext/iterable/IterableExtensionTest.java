package com.mparticle.ext.iterable;

import com.mparticle.iterable.*;
import com.mparticle.sdk.model.audienceprocessing.Audience;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeRequest;
import com.mparticle.sdk.model.audienceprocessing.UserProfile;
import com.mparticle.sdk.model.eventprocessing.*;
import com.mparticle.sdk.model.registration.Account;
import com.mparticle.sdk.model.registration.ModuleRegistrationResponse;
import com.mparticle.sdk.model.registration.Setting;
import com.mparticle.sdk.model.registration.UserIdentityPermission;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import retrofit.Call;
import retrofit.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.Assert.*;

public class IterableExtensionTest {

    @org.junit.Test
    public void testProcessEventProcessingRequest() throws Exception {
        IterableExtension extension = new IterableExtension();
        EventProcessingRequest request = new EventProcessingRequest();
        List<Event> events = new LinkedList<>();
        Event customEvent1 = new UserAttributeChangeEvent();
        customEvent1.setTimestamp(3);
        Event customEvent2 = new UserAttributeChangeEvent();
        customEvent2.setTimestamp(2);
        Event customEvent3 = new UserAttributeChangeEvent();
        customEvent3.setTimestamp(1);
        Event customEvent4 = new UserAttributeChangeEvent();
        customEvent4.setTimestamp(4);
        request.setDeviceApplicationStamp("foo");
        //out of order
        events.add(customEvent1);
        events.add(customEvent2);
        events.add(customEvent3);
        events.add(customEvent4);

        request.setEvents(events);
        Account account = new Account();
        HashMap<String, String> settings = new HashMap<String, String>();
        settings.put(IterableExtension.SETTING_API_KEY, "cool api key");
        account.setAccountSettings(settings);
        request.setAccount(account);
        extension.processEventProcessingRequest(request);
        assertNotNull("IterableService should have been created", extension.iterableService);

        assertEquals("Events should have been in order",1, request.getEvents().get(0).getTimestamp());
        assertEquals("Events should have been in order",2, request.getEvents().get(1).getTimestamp());
        assertEquals("Events should have been in order",3, request.getEvents().get(2).getTimestamp());
        assertEquals("Events should have been in order",4, request.getEvents().get(3).getTimestamp());
    }

    @org.junit.Test
    public void testUpdateUser() throws Exception {
        IterableExtension extension = new IterableExtension();
        extension.iterableService = Mockito.mock(IterableService.class);
        Call callMock = Mockito.mock(Call.class);
        Mockito.when(extension.iterableService.userUpdate(Mockito.any()))
                .thenReturn(callMock);
        IterableApiResponse apiResponse = new IterableApiResponse();
        apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
        Response<IterableApiResponse> response = Response.success(apiResponse);
        Mockito.when(callMock.execute()).thenReturn(response);
        EventProcessingRequest request = new EventProcessingRequest();

        //no user identities, no API call
        extension.updateUser(request);
        UserUpdateRequest userUpdateRequest = new UserUpdateRequest();
        Mockito.verify(extension.iterableService, Mockito.never()).userUpdate(userUpdateRequest);

        //user identities but no email/userid, no API call
        List<UserIdentity> identities = new LinkedList<>();
        identities.add(new UserIdentity(UserIdentity.Type.FACEBOOK, Identity.Encoding.RAW, "123456"));
        request.setUserIdentities(identities);
        extension.updateUser(request);
        Mockito.verify(extension.iterableService, Mockito.never()).userUpdate(userUpdateRequest);

        //ok, now we should get a single API call
        identities.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest@mparticle.com"));
        identities.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "123456"));
        Map<String, String> userAttributes = new HashMap<String, String>();
        userAttributes.put("some attribute key", "some attribute value");
        request.setUserAttributes(userAttributes);
        request.setUserIdentities(identities);

        extension.updateUser(request);

        ArgumentCaptor<UserUpdateRequest> argument = ArgumentCaptor.forClass(UserUpdateRequest.class);
        Mockito.verify(extension.iterableService).userUpdate(argument.capture());
        assertEquals("mptest@mparticle.com", argument.getValue().email);
        assertEquals("123456", argument.getValue().userId);
        assertEquals(argument.getValue().dataFields.get("some attribute key"), "some attribute value");

        apiResponse.code = "anything but success";

        IOException exception = null;
        try {
            extension.updateUser(request);
        } catch (IOException ioe) {
            exception = ioe;
        }
        assertNotNull("Iterable extension should have thrown an IOException", exception);

    }

    @org.junit.Test
    public void testProcessUserAttributeChangeEvent() throws Exception {
        //just verify that we're not processing anything - it's all done in processEventProcessingRequest
        IterableExtension extension = new IterableExtension();
        extension.iterableService = Mockito.mock(IterableService.class);
        extension.processUserAttributeChangeEvent(new UserAttributeChangeEvent());
        Mockito.verify(extension.iterableService, Mockito.never()).userUpdate(Mockito.any());
    }

    /**
     * Simple test to make sure Iterable is registering for the proper data points.
     *
     * @throws Exception
     */
    @org.junit.Test
    public void testProcessRegistrationRequest() throws Exception {
        ModuleRegistrationResponse response = new IterableExtension().processRegistrationRequest(null);
        List<UserIdentityPermission> userIdentities = response.getPermissions().getUserIdentities();
        assertEquals(2, userIdentities.size());
        boolean email, customer;
        email = userIdentities.get(0).getType().equals(UserIdentity.Type.EMAIL) ||
                userIdentities.get(1).getType().equals(UserIdentity.Type.EMAIL);

        customer = userIdentities.get(0).getType().equals(UserIdentity.Type.CUSTOMER) ||
                userIdentities.get(1).getType().equals(UserIdentity.Type.CUSTOMER);


        assertTrue("Iterable Extension should register for email permission", email);
        assertTrue("Iterable Extension should register for customer id permission", customer);

        List<Setting> accountSettings = response.getEventProcessingRegistration().getAccountSettings();
        assertTrue("There should be a single text setting (api key) for iterable", accountSettings.get(0).getType().equals(Setting.Type.TEXT));

        List<Event.Type> eventTypes = response.getEventProcessingRegistration().getSupportedEventTypes();
        assertTrue("Iterable should support custom events", eventTypes.contains(Event.Type.CUSTOM_EVENT));
        assertTrue("Iterable should support push subscriptions", eventTypes.contains(Event.Type.PUSH_SUBSCRIPTION));
        assertTrue("Iterable should support push receipts", eventTypes.contains(Event.Type.PUSH_MESSAGE_RECEIPT));
        assertTrue("Iterable should support user attribute changes", eventTypes.contains(Event.Type.USER_ATTRIBUTE_CHANGE));
        assertTrue("Iterable should support user identity changes", eventTypes.contains(Event.Type.USER_IDENTITY_CHANGE));

        Setting setting = response.getAudienceProcessingRegistration().getAudienceConnectionSettings().get(0);
        assertTrue("Iterable audiences should have a single Integer setting", setting.getType().equals(Setting.Type.INTEGER));
    }

    @org.junit.Test
    public void testProcessCustomEvent() throws Exception {
        IterableExtension extension = new IterableExtension();
        extension.iterableService = Mockito.mock(IterableService.class);
        Call callMock = Mockito.mock(Call.class);
        Mockito.when(extension.iterableService.track(Mockito.any()))
                .thenReturn(callMock);
        IterableApiResponse apiResponse = new IterableApiResponse();
        apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
        Response<IterableApiResponse> response = Response.success(apiResponse);
        Mockito.when(callMock.execute()).thenReturn(response);

        long timeStamp = System.currentTimeMillis();
        CustomEvent event = new CustomEvent();
        event.setTimestamp(timeStamp);
        event.setName("My Event Name");
        EventProcessingRequest request = new EventProcessingRequest();
        List<UserIdentity> userIdentities = new LinkedList<>();
        userIdentities.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest@mparticle.com"));
        userIdentities.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "123456"));
        request.setUserIdentities(userIdentities);
        Event.Context context = new Event.Context(request);
        event.setContext(context);
        Map<String, String> attributes = new HashMap<>();
        attributes.put("some attribute key", "some attribute value");
        event.setAttributes(attributes);

        extension.processCustomEvent(event);

        ArgumentCaptor<TrackRequest> argument = ArgumentCaptor.forClass(TrackRequest.class);
        Mockito.verify(extension.iterableService).track(argument.capture());
        assertEquals("My Event Name", argument.getValue().getEventName());
        assertEquals("mptest@mparticle.com", argument.getValue().email);
        assertEquals("123456", argument.getValue().userId);
        assertEquals("some attribute value", argument.getValue().dataFields.get("some attribute key"));
        assertEquals((int) (timeStamp / 1000.0), argument.getValue().createdAt + 0);

        apiResponse.code = "anything but success";

        IOException exception = null;
        try {
            extension.processCustomEvent(event);
        } catch (IOException ioe) {
            exception = ioe;
        }
        assertNotNull("Iterable extension should have thrown an IOException", exception);
    }

    @org.junit.Test
    public void testProcessAndroidPushMessageReceiptEvent() throws Exception {
        IterableExtension extension = new IterableExtension();
        extension.iterableService = Mockito.mock(IterableService.class);
        Call callMock = Mockito.mock(Call.class);
        Mockito.when(extension.iterableService.trackPushOpen(Mockito.any()))
                .thenReturn(callMock);
        IterableApiResponse apiResponse = new IterableApiResponse();
        apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
        Response<IterableApiResponse> response = Response.success(apiResponse);
        Mockito.when(callMock.execute()).thenReturn(response);
        EventProcessingRequest eventProcessingRequest = new EventProcessingRequest();
        eventProcessingRequest.setUserIdentities(new LinkedList<>());
        PushMessageReceiptEvent event = new PushMessageReceiptEvent();
        event.setContext(new Event.Context(eventProcessingRequest));
        IOException exception = null;
        event.setPayload("anything to get past null check");
        try {
            extension.processPushMessageReceiptEvent(event);
        } catch (IOException ioe) {
            exception = ioe;
        }
        assertNotNull("Iterable should have thrown an exception due to missing email/customerid", exception);

        List<UserIdentity> userIdentities = new LinkedList<>();
        userIdentities.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest@mparticle.com"));
        userIdentities.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "123456"));
        eventProcessingRequest.setUserIdentities(userIdentities);
        eventProcessingRequest.setRuntimeEnvironment(new AndroidRuntimeEnvironment());
        event.setContext(new Event.Context(eventProcessingRequest));
        event.setPayload("{\"google.sent_time\":1507657706679,\"body\":\"example\",\"from\":\"674988899928\",\"itbl\":\"{\\\"campaignId\\\":12345,\\\"isGhostPush\\\":false,\\\"messageId\\\":\\\"1dce4e505b11111ca1111d6fdd774fbd\\\",\\\"templateId\\\":54321}\",\"google.message_id\":\"0:1507657706689231%62399b94f9fd7ecd\"}");

        long timeStamp = System.currentTimeMillis();
        event.setTimestamp(timeStamp);

        extension.processPushMessageReceiptEvent(event);

        ArgumentCaptor<TrackPushOpenRequest> argument = ArgumentCaptor.forClass(TrackPushOpenRequest.class);
        Mockito.verify(extension.iterableService).trackPushOpen(argument.capture());
        assertEquals("mptest@mparticle.com", argument.getValue().email);
        assertEquals("123456", argument.getValue().userId);
        assertEquals(12345, argument.getValue().campaignId + 0);
        assertEquals(54321, argument.getValue().templateId + 0);
        assertEquals("\"1dce4e505b11111ca1111d6fdd774fbd\"", argument.getValue().messageId);


        apiResponse.code = "anything but success";

        IOException exception2 = null;
        try {
            extension.processPushMessageReceiptEvent(event);
        } catch (IOException ioe) {
            exception2 = ioe;
        }
        assertNotNull("Iterable extension should have thrown an IOException", exception2);

    }

    @org.junit.Test
    public void testProcessiOSPushMessageReceiptEvent() throws Exception {
        IterableExtension extension = new IterableExtension();
        extension.iterableService = Mockito.mock(IterableService.class);
        Call callMock = Mockito.mock(Call.class);
        Mockito.when(extension.iterableService.trackPushOpen(Mockito.any()))
                .thenReturn(callMock);
        IterableApiResponse apiResponse = new IterableApiResponse();
        apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
        Response<IterableApiResponse> response = Response.success(apiResponse);
        Mockito.when(callMock.execute()).thenReturn(response);
        EventProcessingRequest eventProcessingRequest = new EventProcessingRequest();
        eventProcessingRequest.setUserIdentities(new LinkedList<>());
        PushMessageReceiptEvent event = new PushMessageReceiptEvent();
        event.setContext(new Event.Context(eventProcessingRequest));
        IOException exception = null;
        event.setPayload("anything to get past null check");
        try {
            extension.processPushMessageReceiptEvent(event);
        } catch (IOException ioe) {
            exception = ioe;
        }
        assertNotNull("Iterable should have thrown an exception due to missing email/customerid", exception);

        List<UserIdentity> userIdentities = new LinkedList<>();
        userIdentities.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest@mparticle.com"));
        userIdentities.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "123456"));
        eventProcessingRequest.setUserIdentities(userIdentities);
        eventProcessingRequest.setRuntimeEnvironment(new IosRuntimeEnvironment());
        event.setContext(new Event.Context(eventProcessingRequest));

        event.setPayload("{\"aps\":{\"content-available\":1 }, \"data\":{\"route\":\"example\", \"tag\":\"example\", \"body\":\"example\"}, \"route\":\"example\", \"type\":\"marketing\", \"itbl\":{\"campaignId\":12345, \"messageId\":\"1dce4e505b11111ca1111d6fdd774fbd\", \"templateId\":54321, \"isGhostPush\":false } }");

        long timeStamp = System.currentTimeMillis();
        event.setTimestamp(timeStamp);

        extension.processPushMessageReceiptEvent(event);

        ArgumentCaptor<TrackPushOpenRequest> argument = ArgumentCaptor.forClass(TrackPushOpenRequest.class);
        Mockito.verify(extension.iterableService).trackPushOpen(argument.capture());
        assertEquals("mptest@mparticle.com", argument.getValue().email);
        assertEquals("123456", argument.getValue().userId);
        assertEquals(12345, argument.getValue().campaignId + 0);
        assertEquals(54321, argument.getValue().templateId + 0);
        assertEquals("\"1dce4e505b11111ca1111d6fdd774fbd\"", argument.getValue().messageId);


        apiResponse.code = "anything but success";

        IOException exception2 = null;
        try {
            extension.processPushMessageReceiptEvent(event);
        } catch (IOException ioe) {
            exception2 = ioe;
        }
        assertNotNull("Iterable extension should have thrown an IOException", exception2);

    }

    /**
     * This test creates 3 audiences and 2 users.
     * <p>
     * User 1: Two audiences added, 1 removed
     * User 2: 1 audience removed, 2 added
     * <p>
     * It then verifies that subscribe/unsubcribe are called the correct amount and with the right list ids
     */
    @org.junit.Test
    public void testProcessAudienceMembershipChangeRequest() throws Exception {
        IterableExtension extension = new IterableExtension();
        IterableService service = Mockito.mock(IterableService.class);
        Call callMock = Mockito.mock(Call.class);
        Mockito.when(service.trackPushOpen(Mockito.any()))
                .thenReturn(callMock);
        IterableApiResponse apiResponse = new IterableApiResponse();
        apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
        Response<IterableApiResponse> response = Response.success(apiResponse);
        Mockito.when(callMock.execute()).thenReturn(response);

        Audience audience = new Audience();
        Map<String, String> audienceSubscriptionSettings = new HashMap<>();
        audienceSubscriptionSettings.put(IterableExtension.SETTING_LIST_ID, "1");
        audience.setAudienceSubscriptionSettings(audienceSubscriptionSettings);

        Audience audience2 = new Audience();
        Map<String, String> audienceSubscriptionSettings2 = new HashMap<>();
        audienceSubscriptionSettings2.put(IterableExtension.SETTING_LIST_ID, "2");
        audience2.setAudienceSubscriptionSettings(audienceSubscriptionSettings2);

        Audience audience3 = new Audience();
        Map<String, String> audienceSubscriptionSettings3 = new HashMap<>();
        audienceSubscriptionSettings3.put(IterableExtension.SETTING_LIST_ID, "3");
        audience3.setAudienceSubscriptionSettings(audienceSubscriptionSettings3);

        List<Audience> list1 = new LinkedList<>();
        list1.add(audience);
        list1.add(audience2);

        List<Audience> list2 = new LinkedList<>();
        list2.add(audience3);

        List<UserProfile> profiles = new LinkedList<>();
        UserProfile profile1 = new UserProfile();
        profile1.setAddedAudiences(list1);
        profile1.setRemovedAudiences(list2);
        List<UserIdentity> userIdentities1 = new LinkedList<>();
        userIdentities1.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest@mparticle.com"));
        userIdentities1.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "123456"));
        profile1.setUserIdentities(userIdentities1);
        profiles.add(profile1);

        UserProfile profile2 = new UserProfile();
        profile2.setAddedAudiences(list2);
        profile2.setRemovedAudiences(list1);
        List<UserIdentity> userIdentities2 = new LinkedList<>();
        userIdentities2.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest-2@mparticle.com"));
        userIdentities2.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "1234567"));
        profile2.setUserIdentities(userIdentities2);
        profiles.add(profile2);

        AudienceMembershipChangeRequest request = new AudienceMembershipChangeRequest();
        Account account = new Account();
        Map<String, String> settings = new HashMap<>();
        settings.put(IterableExtension.SETTING_API_KEY, "some api key");
        account.setAccountSettings(settings);
        request.setAccount(account);
        request.setUserProfiles(profiles);

        extension.processAudienceMembershipChangeRequest(request, service);

        ArgumentCaptor<SubscribeRequest> argument = ArgumentCaptor.forClass(SubscribeRequest.class);
        Mockito.verify(service, Mockito.times(3)).listSubscribe(argument.capture());
        List<SubscribeRequest> subscribeRequests = argument.getAllValues();
        int i = 0;
        for (SubscribeRequest subscribeRequest : subscribeRequests) {
            switch (subscribeRequest.listId) {
                case 1:
                    assertEquals(subscribeRequest.subscribers.get(0).email, "mptest@mparticle.com");
                    i++;
                    break;
                case 2:
                    assertEquals(subscribeRequest.subscribers.get(0).email, "mptest@mparticle.com");
                    i++;
                    break;
                case 3:
                    assertEquals(subscribeRequest.subscribers.get(0).email, "mptest-2@mparticle.com");
                    i++;
                    break;
            }
        }
        assertEquals(3, i);

        ArgumentCaptor<UnsubscribeRequest> unsubArg = ArgumentCaptor.forClass(UnsubscribeRequest.class);
        Mockito.verify(service, Mockito.times(3)).listUnsubscribe(unsubArg.capture());
        List<UnsubscribeRequest> unsubscribeRequests = unsubArg.getAllValues();
        i = 0;
        for (UnsubscribeRequest unsubscribeRequest : unsubscribeRequests) {
            switch (unsubscribeRequest.listId) {
                case 1:
                    assertEquals(unsubscribeRequest.subscribers.get(0).email, "mptest-2@mparticle.com");
                    i++;
                    break;
                case 2:
                    assertEquals(unsubscribeRequest.subscribers.get(0).email, "mptest-2@mparticle.com");
                    i++;
                    break;
                case 3:
                    assertEquals(unsubscribeRequest.subscribers.get(0).email, "mptest@mparticle.com");
                    i++;
                    break;
            }
        }
        assertEquals(3, i);
    }

    @org.junit.Test
    public void testConvertToCommerceItem() throws Exception {
        Product product = new Product();
        product.setId("some id");
        product.setName("some name");
        product.setCategory("some category");
        Map<String, String> attributes = new HashMap<>();
        attributes.put("a key", "a value");
        product.setAttributes(attributes);
        product.setQuantity(new BigDecimal(1.4));
        CommerceItem item = new IterableExtension().convertToCommerceItem(product);
        assertEquals("some id", item.id);
        assertEquals("some id", item.sku);
        assertEquals("some name", item.name);
        assertEquals("some category", item.categories.get(0));
        assertEquals("a value", item.dataFields.get("a key"));
        assertEquals((Integer) new BigDecimal(1.4).intValue(), item.quantity);
    }

    @org.junit.Test
    public void testProcessProductActionEvent() throws Exception {
        ProductActionEvent event = new ProductActionEvent();
        IterableExtension extension = new IterableExtension();
        extension.iterableService = Mockito.mock(IterableService.class);
        Call callMock = Mockito.mock(Call.class);
        Mockito.when(extension.iterableService.trackPurchase(Mockito.any()))
                .thenReturn(callMock);
        IterableApiResponse apiResponse = new IterableApiResponse();
        apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
        Response<IterableApiResponse> response = Response.success(apiResponse);
        Mockito.when(callMock.execute()).thenReturn(response);
        long timeStamp = System.currentTimeMillis();

        event.setTimestamp(timeStamp);

        EventProcessingRequest request = new EventProcessingRequest();
        List<UserIdentity> userIdentities = new LinkedList<>();
        userIdentities.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest@mparticle.com"));
        userIdentities.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "123456"));
        request.setUserIdentities(userIdentities);
        Event.Context context = new Event.Context(request);
        event.setContext(context);
        event.setTotalAmount(new BigDecimal(101d));
        List<Product> products = new LinkedList<>();
        Product product1 = new Product();
        product1.setId("product_id_1");
        Product product2 = new Product();
        product2.setId("product_id_2");
        products.add(product1);
        products.add(product2);
        event.setProducts(products);

        for (ProductActionEvent.Action action : ProductActionEvent.Action.values()) {
            if (action != ProductActionEvent.Action.PURCHASE) {
                event.setAction(action);
                extension.processProductActionEvent(event);
                Mockito.verifyZeroInteractions(extension.iterableService);
            }
        }

        event.setAction(ProductActionEvent.Action.PURCHASE);
        extension.processProductActionEvent(event);
        ArgumentCaptor<TrackPurchaseRequest> purchaseArgs = ArgumentCaptor.forClass(TrackPurchaseRequest.class);
        Mockito.verify(extension.iterableService, Mockito.times(1)).trackPurchase(purchaseArgs.capture());
        TrackPurchaseRequest trackPurchaseRequest = purchaseArgs.getValue();
        assertEquals(trackPurchaseRequest.user.email, "mptest@mparticle.com");
        assertEquals(trackPurchaseRequest.user.userId, "123456");
        assertEquals(trackPurchaseRequest.items.size(), 2);
        assertEquals(trackPurchaseRequest.total, new BigDecimal(101d));
    }

    @Test
    public void testGetPlaceholderEmailNoEnvironmentOrStamp() throws Exception {
        EventProcessingRequest request = new EventProcessingRequest();
        request.setRuntimeEnvironment(null);
        request.setDeviceApplicationStamp(null);
        Exception e = null;
        try {
            String email = IterableExtension.getPlaceholderEmail(request);
        }catch (IOException ioe) {
            e = ioe;
        }
        assertNotNull(e);
    }

    @Test
    public void testGetPlaceholderEmailNoEnvironment() throws Exception {
        EventProcessingRequest request = new EventProcessingRequest();
        request.setRuntimeEnvironment(null);
        request.setDeviceApplicationStamp("1234");
        String email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("1234@placeholder.email", email);
    }

    @Test
    public void testGetPlaceholderEmailEnvironmentButNoIds() throws Exception {
        EventProcessingRequest request = new EventProcessingRequest();
        request.setRuntimeEnvironment(new AndroidRuntimeEnvironment());
        request.setDeviceApplicationStamp("1234");
        String email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("1234@placeholder.email", email);

        request = new EventProcessingRequest();
        request.setRuntimeEnvironment(new IosRuntimeEnvironment());
        request.setDeviceApplicationStamp("12345");
        email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("12345@placeholder.email", email);

        request = new EventProcessingRequest();
        request.setRuntimeEnvironment(new TVOSRuntimeEnvironment());
        request.setDeviceApplicationStamp("123456");
        email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("123456@placeholder.email", email);
    }

    @Test
    public void testGetPlaceholderEmailEnvironmentIDFA() throws Exception {
        EventProcessingRequest request = new EventProcessingRequest();
        request.setRuntimeEnvironment(new IosRuntimeEnvironment());
        DeviceIdentity idfa = new DeviceIdentity(DeviceIdentity.Type.IOS_ADVERTISING_ID, Identity.Encoding.RAW, "foo-idfa");
        ((IosRuntimeEnvironment)request.getRuntimeEnvironment()).setIdentities(Arrays.asList(idfa));
        request.setDeviceApplicationStamp("1234");
        String email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("foo-idfa@placeholder.email", email);

        request.setRuntimeEnvironment(new TVOSRuntimeEnvironment());
        ((TVOSRuntimeEnvironment)request.getRuntimeEnvironment()).setIdentities(Arrays.asList(idfa));
        request.setDeviceApplicationStamp("1234");
        email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("foo-idfa@placeholder.email", email);
    }

    @Test
    public void testGetPlaceholderEmailEnvironmentIDFV() throws Exception {
        EventProcessingRequest request = new EventProcessingRequest();
        request.setRuntimeEnvironment(new IosRuntimeEnvironment());
        DeviceIdentity idfv = new DeviceIdentity(DeviceIdentity.Type.IOS_VENDOR_ID, Identity.Encoding.RAW, "foo-idfv");
        DeviceIdentity idfa = new DeviceIdentity(DeviceIdentity.Type.IOS_ADVERTISING_ID, Identity.Encoding.RAW, "foo-idfa");
        ((IosRuntimeEnvironment)request.getRuntimeEnvironment()).setIdentities(Arrays.asList(idfa, idfv));
        request.setDeviceApplicationStamp("1234");
        String email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("foo-idfv@placeholder.email", email);

        request.setRuntimeEnvironment(new TVOSRuntimeEnvironment());
        ((TVOSRuntimeEnvironment)request.getRuntimeEnvironment()).setIdentities(Arrays.asList(idfa, idfv));
        request.setDeviceApplicationStamp("1234");
        email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("foo-idfv@placeholder.email", email);
    }

    @Test
    public void testGetPlaceholderEmailEnvironmentGAID() throws Exception {
        EventProcessingRequest request = new EventProcessingRequest();
        request.setRuntimeEnvironment(new AndroidRuntimeEnvironment());
        DeviceIdentity idfv = new DeviceIdentity(DeviceIdentity.Type.ANDROID_ID, Identity.Encoding.RAW, "foo-aid");
        DeviceIdentity idfa = new DeviceIdentity(DeviceIdentity.Type.GOOGLE_ADVERTISING_ID, Identity.Encoding.RAW, "foo-gaid");
        ((AndroidRuntimeEnvironment)request.getRuntimeEnvironment()).setIdentities(Arrays.asList(idfa, idfv));
        request.setDeviceApplicationStamp("1234");
        String email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("foo-gaid@placeholder.email", email);
    }

    @Test
    public void testGetPlaceholderEmailEnvironmentAndroidID() throws Exception {
        EventProcessingRequest request = new EventProcessingRequest();
        request.setRuntimeEnvironment(new AndroidRuntimeEnvironment());
        DeviceIdentity idfv = new DeviceIdentity(DeviceIdentity.Type.ANDROID_ID, Identity.Encoding.RAW, "foo-aid");
        ((AndroidRuntimeEnvironment)request.getRuntimeEnvironment()).setIdentities(Arrays.asList(idfv));
        request.setDeviceApplicationStamp("1234");
        String email = IterableExtension.getPlaceholderEmail(request);
        assertEquals("foo-aid@placeholder.email", email);
    }

    @org.junit.Test
    public void testUnsubscribeEvent() throws Exception {
        IterableExtension extension = new IterableExtension();
        extension.iterableService = Mockito.mock(IterableService.class);
        Call callMock = Mockito.mock(Call.class);
        Mockito.when(extension.iterableService.updateSubscriptions(Mockito.any()))
                .thenReturn(callMock);
        IterableApiResponse apiResponse = new IterableApiResponse();
        apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
        Response<IterableApiResponse> response = Response.success(apiResponse);
        Mockito.when(callMock.execute()).thenReturn(response);

        long timeStamp = System.currentTimeMillis();
        CustomEvent event = new CustomEvent();
        event.setTimestamp(timeStamp);
        event.setName("Unsubscribe");
        EventProcessingRequest request = new EventProcessingRequest();
        List<UserIdentity> userIdentities = new LinkedList<>();
        userIdentities.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest@mparticle.com"));
        userIdentities.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "123456"));
        request.setUserIdentities(userIdentities);
        Event.Context context = new Event.Context(request);
        event.setContext(context);
        Map<String, String> attributes = new HashMap<>();
        attributes.put("unsubscribeMessageTypeIdList", "1,2,3,4");
        event.setAttributes(attributes);
        List<Integer> expectedMessageTypeIdList = Arrays.asList(1, 2, 3, 4);

        extension.processCustomEvent(event);

        ArgumentCaptor<UserUpdateRequest> argument = ArgumentCaptor.forClass(UserUpdateRequest.class);
        Mockito.verify(extension.iterableService).updateSubscriptions(argument.capture());
        assertEquals("mptest@mparticle.com", argument.getValue().email);
        assertEquals("123456", argument.getValue().userId);
        assertEquals(expectedMessageTypeIdList, argument.getValue().dataFields.get("unsubscribeMessageTypeIdList"));

        apiResponse.code = "anything but success";

        IOException exception = null;
        try {
            extension.processCustomEvent(event);
        } catch (IOException ioe) {
            exception = ioe;
        }
        assertNotNull("Iterable extension should have thrown an IOException", exception);
    }

    @org.junit.Test
    public void testSubscribeEvent() throws Exception {
        IterableExtension extension = new IterableExtension();
        extension.iterableService = Mockito.mock(IterableService.class);
        Call callMock = Mockito.mock(Call.class);
        Mockito.when(extension.iterableService.updateSubscriptions(Mockito.any()))
                .thenReturn(callMock);
        IterableApiResponse apiResponse = new IterableApiResponse();
        apiResponse.code = IterableApiResponse.SUCCESS_MESSAGE;
        Response<IterableApiResponse> response = Response.success(apiResponse);
        Mockito.when(callMock.execute()).thenReturn(response);

        long timeStamp = System.currentTimeMillis();
        CustomEvent event = new CustomEvent();
        event.setTimestamp(timeStamp);
        event.setName("Subscribe");
        EventProcessingRequest request = new EventProcessingRequest();
        List<UserIdentity> userIdentities = new LinkedList<>();
        userIdentities.add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, "mptest@mparticle.com"));
        userIdentities.add(new UserIdentity(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, "123456"));
        request.setUserIdentities(userIdentities);
        Event.Context context = new Event.Context(request);
        event.setContext(context);
        Map<String, String> attributes = new HashMap<>();
        // Added some random spaces to test it doesn't mess with parsing
        attributes.put("allMessageTypeIdList", "1, 2,  3, 4 , 5 , 6 , 7  ,8");
        attributes.put("subscribeMessageTypeIdList", " 1, 3, 5   ,7 ");
        event.setAttributes(attributes);
        List<Integer> expectedMessageTypeIdList = Arrays.asList(2, 4, 6, 8);

        extension.processCustomEvent(event);

        ArgumentCaptor<UserUpdateRequest> argument = ArgumentCaptor.forClass(UserUpdateRequest.class);
        Mockito.verify(extension.iterableService).updateSubscriptions(argument.capture());
        assertEquals("mptest@mparticle.com", argument.getValue().email);
        assertEquals("123456", argument.getValue().userId);
        assertEquals(expectedMessageTypeIdList, argument.getValue().dataFields.get("unsubscribeMessageTypeIdList"));

        apiResponse.code = "anything but success";

        IOException exception = null;
        try {
            extension.processCustomEvent(event);
        } catch (IOException ioe) {
            exception = ioe;
        }
        assertNotNull("Iterable extension should have thrown an IOException", exception);
    }
}
