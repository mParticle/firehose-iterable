package com.mparticle.ext.iterable;

import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;
import com.mparticle.iterable.*;
import com.mparticle.sdk.MessageProcessor;
import com.mparticle.sdk.model.audienceprocessing.Audience;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeRequest;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeResponse;
import com.mparticle.sdk.model.audienceprocessing.UserProfile;
import com.mparticle.sdk.model.eventprocessing.*;
import com.mparticle.sdk.model.registration.*;
import org.apache.http.util.TextUtils;
import retrofit.Response;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class IterableExtension extends MessageProcessor {

    public static final String NAME = "Iterable";
    public static final String SETTING_API_KEY = "apiKey";
    public static final String SETTING_GCM_NAME_KEY = "gcmIntegrationName";
    public static final String SETTING_APNS_KEY = "apnsProdIntegrationName";
    public static final String SETTING_APNS_SANDBOX_KEY = "apnsSandboxIntegrationName";
    public static final String SETTING_LIST_ID = "listId";
    IterableService iterableService;

    @Override
    public EventProcessingResponse processEventProcessingRequest(EventProcessingRequest request) throws IOException {
        if (iterableService == null) {
            Account account = request.getAccount();
            String apiKey = account.getStringSetting(SETTING_API_KEY, true, null);
            iterableService = IterableService.newInstance(apiKey);
        }
        updateUser(new Event.Context(request));
        return super.processEventProcessingRequest(request);
    }

    @Override
    public void processPushSubscriptionEvent(PushSubscriptionEvent event) throws IOException {
        RegisterDeviceTokenRequest request = new RegisterDeviceTokenRequest();
        request.device = new Device();
        if (event.getContext().getRuntimeEnvironment().getType().equals(RuntimeEnvironment.Type.IOS)) {
            Boolean sandboxed = ((IosRuntimeEnvironment) event.getContext().getRuntimeEnvironment()).getIsSandboxed();
            if (sandboxed != null && sandboxed) {
                request.device.platform = Device.PLATFORM_APNS_SANDBOX;
                request.device.applicationName = event.getContext().getAccount().getAccountSettings().get(SETTING_APNS_SANDBOX_KEY);
            } else {
                request.device.platform = Device.PLATFORM_APNS;
                request.device.applicationName = event.getContext().getAccount().getAccountSettings().get(SETTING_APNS_KEY);
            }
        } else if (event.getContext().getRuntimeEnvironment().getType().equals(RuntimeEnvironment.Type.ANDROID)) {
            request.device.platform = Device.PLATFORM_GCM;
            request.device.applicationName = event.getContext().getAccount().getAccountSettings().get(SETTING_GCM_NAME_KEY);
        } else {
            throw new IOException("Cannot process push subscription event for unknown RuntimeEnvironment type.");
        }

        request.device.token = event.getToken();

        try {
            UserIdentity email = event.getContext().getUserIdentities().stream().filter(t -> t.getType().equals(UserIdentity.Type.EMAIL))
                    .findFirst()
                    .get();
            request.email = email.getValue();
        } catch (NoSuchElementException e) {
            throw new IOException("Unable to construct Iterable RegisterDeviceTokenRequest - no user email.");
        }

        Response<IterableApiResponse> response = iterableService.registerToken(request).execute();
        if (response.isSuccess() && !response.body().isSuccess()) {
            throw new IOException(response.body().toString());
        } else if (!response.isSuccess()) {
            throw new IOException("Error sending push subscription to Iterable: HTTP " + response.code());
        }
    }

    void updateUser(Event.Context context) throws IOException {
        List<UserIdentity> identities = context.getUserIdentities();
        UserUpdateRequest userUpdateRequest = new UserUpdateRequest();
        if (identities != null) {
            for (UserIdentity identity : identities) {
                if (identity.getType().equals(UserIdentity.Type.EMAIL)) {
                    userUpdateRequest.email = identity.getValue();
                } else if (identity.getType().equals(UserIdentity.Type.CUSTOMER)) {
                    userUpdateRequest.userId = identity.getValue();
                }
            }
            if (!TextUtils.isEmpty(userUpdateRequest.email) || !TextUtils.isEmpty(userUpdateRequest.userId)) {
                userUpdateRequest.dataFields = context.getUserAttributes();
                Response<IterableApiResponse> response = iterableService.userUpdate(userUpdateRequest).execute();
                if (response.isSuccess()) {
                    IterableApiResponse apiResponse = response.body();
                    if (apiResponse != null && !apiResponse.isSuccess()) {
                        throw new IOException(apiResponse.toString());
                    }
                }
            }
        }
    }

    @Override
    public void processProductActionEvent(ProductActionEvent event) throws IOException {
        if (event.getAction().equals(ProductActionEvent.Action.PURCHASE)) {
            TrackPurchaseRequest purchaseRequest = new TrackPurchaseRequest();
            purchaseRequest.createdAt = (int) (event.getTimestamp() / 1000.0);
            List<UserIdentity> identities = event.getContext().getUserIdentities();
            ApiUser apiUser = new ApiUser();
            if (identities != null) {
                for (UserIdentity identity : identities) {
                    if (identity.getType().equals(UserIdentity.Type.EMAIL)) {
                        apiUser.email = identity.getValue();
                    } else if (identity.getType().equals(UserIdentity.Type.CUSTOMER)) {
                        apiUser.userId = identity.getValue();
                    }
                }
            }
            apiUser.dataFields = event.getContext().getUserAttributes();
            purchaseRequest.user = apiUser;
            purchaseRequest.total = event.getTotalAmount();
            if (event.getProducts() != null) {
                purchaseRequest.items = event.getProducts().stream()
                        .map(p -> convertToCommerceItem(p))
                        .collect(Collectors.toList());
            }

            Response<IterableApiResponse> response = iterableService.trackPurchase(purchaseRequest).execute();
            if (response.isSuccess() && !response.body().isSuccess()) {
                throw new IOException(response.body().toString());
            } else if (!response.isSuccess()) {
                throw new IOException("Error sending custom event to Iterable: HTTP " + response.code());
            }
        }
    }

    CommerceItem convertToCommerceItem(Product product) {
        CommerceItem item = new CommerceItem();
        item.dataFields = product.getAttributes();
        //iterable requires ID, and they also take SKU. mParticle doesn't differentiate
        //between sku and id. so, use our sku/id for both in Iterable:
        item.id = item.sku = product.getId();
        item.name = product.getName();
        item.price = product.getPrice();
        if (product.getQuantity() != null) {
            item.quantity = product.getQuantity().intValue();
        }
        if (product.getCategory() != null) {
            item.categories = new LinkedList<>();
            item.categories.add(product.getCategory());
        }
        return item;
    }

    @Override
    public void processUserAttributeChangeEvent(UserAttributeChangeEvent event) throws IOException {
        //there's no reason to do this - it's already done at the start of batch processing
        //updateUser(event.getContext());
    }

    @Override
    public ModuleRegistrationResponse processRegistrationRequest(ModuleRegistrationRequest request) {
        ModuleRegistrationResponse response = new ModuleRegistrationResponse(NAME, "1.0");

        Permissions permissions = new Permissions();
        permissions.setUserIdentities(
                Arrays.asList(
                        new UserIdentityPermission(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, true),
                        new UserIdentityPermission(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, true)
                )
        );
        permissions.setDeviceIdentities(
                Arrays.asList(
                        new DeviceIdentityPermission(DeviceIdentity.Type.GOOGLE_CLOUD_MESSAGING_TOKEN, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.APPLE_PUSH_NOTIFICATION_TOKEN, Identity.Encoding.RAW)
                )
        );
        response.setPermissions(permissions);
        response.setDescription("Iterable makes consumer growth marketing and user engagement simple. With Iterable, marketers send the right message, to the right device, at the right time.");
        EventProcessingRegistration eventProcessingRegistration = new EventProcessingRegistration()
                .setSupportedRuntimeEnvironments(
                        Arrays.asList(
                                RuntimeEnvironment.Type.ANDROID,
                                RuntimeEnvironment.Type.IOS)
                );

        List<Setting> eventSettings = new ArrayList<>();
        List<Setting> audienceSettings = new ArrayList<>();
        Setting apiKey = new TextSetting(SETTING_API_KEY, "API Key")
                .setIsRequired(true)
                .setIsConfidential(true)
                .setDescription("API key used to connect to the Iterable API - see the Integrations section of your Iterable account.");

        eventSettings.add(apiKey);
        audienceSettings.add(apiKey);

        eventSettings.add(
                new TextSetting(SETTING_GCM_NAME_KEY, "GCM Push Integration Name")
                        .setIsRequired(false)
                        .setDescription("GCM integration name set up in the Mobile Push section of your Iterable account.")

        );
        eventSettings.add(
                new TextSetting(SETTING_APNS_SANDBOX_KEY, "APNS Sandbox Integration Name")
                        .setIsRequired(false)
                        .setDescription("APNS Sandbox integration name set up in the Mobile Push section of your Iterable account.")
        );
        eventSettings.add(
                new TextSetting(SETTING_APNS_KEY, "APNS Production Integration Name")
                        .setIsRequired(false)
                        .setDescription("APNS Production integration name set up in the Mobile Push section of your Iterable account.")
        );
        eventProcessingRegistration.setAccountSettings(eventSettings);

        // Specify supported event types
        List<Event.Type> supportedEventTypes = Arrays.asList(
                Event.Type.CUSTOM_EVENT,
                Event.Type.PUSH_SUBSCRIPTION,
                Event.Type.PUSH_MESSAGE_RECEIPT,
                Event.Type.USER_ATTRIBUTE_CHANGE,
                Event.Type.USER_IDENTITY_CHANGE,
                Event.Type.PRODUCT_ACTION);

        eventProcessingRegistration.setSupportedEventTypes(supportedEventTypes);
        response.setEventProcessingRegistration(eventProcessingRegistration);

        AudienceProcessingRegistration audienceRegistration = new AudienceProcessingRegistration();
        audienceRegistration.setAccountSettings(audienceSettings);
        List<Setting> subscriptionSettings = new LinkedList<>();
        IntegerSetting listIdSetting = new IntegerSetting(SETTING_LIST_ID, "List ID");
        listIdSetting.setIsRequired(true);
        listIdSetting.setDescription("The ID of the Iterable list to populate with the users from this segment.");
        subscriptionSettings.add(listIdSetting);
        audienceRegistration.setAudienceSubscriptionSettings(subscriptionSettings);
        response.setAudienceProcessingRegistration(audienceRegistration);

        return response;
    }

    @Override
    public void processCustomEvent(CustomEvent event) throws IOException {
        TrackRequest request = new TrackRequest(event.getName());
        request.createdAt = (int) (event.getTimestamp() / 1000.0);
        request.dataFields = event.getAttributes();
        List<UserIdentity> identities = event.getContext().getUserIdentities();
        if (identities != null) {
            for (UserIdentity identity : identities) {
                if (identity.getType().equals(UserIdentity.Type.EMAIL)) {
                    request.email = identity.getValue();
                } else if (identity.getType().equals(UserIdentity.Type.CUSTOMER)) {
                    request.userId = identity.getValue();
                }
            }
        }
        //TODO use custom flags to set campaign and template id
        //request.campaignId = event.getCustomFlags()....
        //request.templateId = event.getCustomFlags()....

        Response<IterableApiResponse> response = iterableService.track(request).execute();
        if (response.isSuccess() && !response.body().isSuccess()) {
            throw new IOException(response.body().toString());
        } else if (!response.isSuccess()) {
            throw new IOException("Error sending custom event to Iterable: HTTP " + response.code());
        }
    }


    @Override
    public void processPushMessageReceiptEvent(PushMessageReceiptEvent event) throws IOException {
        TrackPushOpenRequest request = new TrackPushOpenRequest();
        List<UserIdentity> identities = event.getContext().getUserIdentities();
        if (event.getPayload() != null && event.getContext().getUserIdentities() != null) {
            for (UserIdentity identity : identities) {
                if (identity.getType().equals(UserIdentity.Type.EMAIL)) {
                    request.email = identity.getValue();
                } else if (identity.getType().equals(UserIdentity.Type.CUSTOMER)) {
                    request.userId = identity.getValue();
                }
            }
            if (request.email == null && request.userId == null) {
                throw new IOException("Unable to process PushMessageReceiptEvent - user has no email or customer id.");
            }
            try {
                JSONObject payload = new JSONObject(event.getPayload());
                if (payload.has("itbl")) {
                    JSONObject iterableObject = payload.getJSONObject("itbl");
                    request.campaignId = iterableObject.optInt("campaignId");
                    request.templateId = iterableObject.optInt("templateId");
                    request.createdAt = (int) (event.getTimestamp() / 1000.0);
                    Response<IterableApiResponse> response = iterableService.trackPushOpen(request).execute();
                    if (response.isSuccess() && !response.body().isSuccess()) {
                        throw new IOException(response.body().toString());
                    } else if (!response.isSuccess()) {
                        throw new IOException("Error sending push-open to Iterable: HTTP " + response.code());
                    }
                }
            } catch (JSONException jse) {
                throw new IOException(jse);
            }
        }
    }

    @Override
    public AudienceMembershipChangeResponse processAudienceMembershipChangeRequest(AudienceMembershipChangeRequest request) throws IOException {
        Map<String, String> settings = request.getAccount().getAccountSettings();
        String apiKey = settings.get(SETTING_API_KEY);
        IterableService service = IterableService.newInstance(apiKey);
        return processAudienceMembershipChangeRequest(request, service);
    }

    public AudienceMembershipChangeResponse processAudienceMembershipChangeRequest(AudienceMembershipChangeRequest request, IterableService service) throws IOException {
        HashMap<Integer, List<ApiUser>> additions = new HashMap<>();
        HashMap<Integer, List<Unsubscriber>> removals = new HashMap<>();
        for (UserProfile profile : request.getUserProfiles()) {
            String email = null, userId = null;
            List<UserIdentity> identities = profile.getUserIdentities();
            for (UserIdentity identity : identities) {
                if (identity.getType().equals(UserIdentity.Type.EMAIL)) {
                    email = identity.getValue();
                } else if (identity.getType().equals(UserIdentity.Type.CUSTOMER)) {
                    userId = identity.getValue();
                }
            }
            if (email != null) {
                if (profile.getAddedAudiences() != null) {
                    for (Audience audience : profile.getAddedAudiences()) {
                        Map<String, String> audienceSettings = audience.getAudienceSubscriptionSettings();
                        int listId = Integer.parseInt(audienceSettings.get(SETTING_LIST_ID));
                        ApiUser user = new ApiUser();
                        user.email = email;
                        user.userId = userId;
                        user.dataFields = profile.getUserAttributes();
                        if (!additions.containsKey(listId)) {
                            additions.put(listId, new LinkedList<>());
                        }
                        additions.get(listId).add(user);
                    }
                }
                if (profile.getRemovedAudiences() != null) {
                    for (Audience audience : profile.getRemovedAudiences()) {
                        Map<String, String> audienceSettings = audience.getAudienceSubscriptionSettings();
                        int listId = Integer.parseInt(audienceSettings.get(SETTING_LIST_ID));
                        Unsubscriber unsubscriber = new Unsubscriber();
                        unsubscriber.email = email;
                        if (!removals.containsKey(listId)) {
                            removals.put(listId, new LinkedList<>());
                        }
                        removals.get(listId).add(unsubscriber);
                    }
                }
            }
        }

        for (Map.Entry<Integer, List<ApiUser>> entry : additions.entrySet()) {
            SubscribeRequest subscribeRequest = new SubscribeRequest();
            subscribeRequest.listId = entry.getKey();
            subscribeRequest.subscribers = entry.getValue();
            try {
                Response<ListResponse> response = service.listSubscribe(subscribeRequest).execute();
                if (response.isSuccess()) {
                    ListResponse listResponse = response.body();
                    if (listResponse.failCount > 0) {
                        throw new IOException("Iterable list subscribe had positive fail count: " + listResponse.failCount);
                    }
                } else if (!response.isSuccess()) {
                    throw new IOException("Error sending list subscribe to Iterable: HTTP " + response.code());
                }
            } catch (Exception e) {

            }
        }

        for (Map.Entry<Integer, List<Unsubscriber>> entry : removals.entrySet()) {
            UnsubscribeRequest unsubscribeRequest = new UnsubscribeRequest();
            unsubscribeRequest.listId = entry.getKey();
            unsubscribeRequest.subscribers = entry.getValue();
            try {
                Response<ListResponse> response = service.listUnsubscribe(unsubscribeRequest).execute();
                if (response.isSuccess()) {
                    ListResponse listResponse = response.body();
                    if (listResponse.failCount > 0) {
                        throw new IOException("Iterable list unsubscribe had positive fail count: " + listResponse.failCount);
                    }
                } else if (!response.isSuccess()) {
                    throw new IOException("Error sending list unsubscribe to Iterable: HTTP " + response.code());
                }
            } catch (Exception e) {

            }
        }
        return new AudienceMembershipChangeResponse();
    }

}
