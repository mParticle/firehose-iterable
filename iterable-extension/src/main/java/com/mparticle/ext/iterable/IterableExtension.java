package com.mparticle.ext.iterable;

import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;
import com.mparticle.iterable.*;
import com.mparticle.sdk.MessageProcessor;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChange;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeRequest;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeResponse;
import com.mparticle.sdk.model.eventprocessing.*;
import com.mparticle.sdk.model.registration.*;
import retrofit.Response;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class IterableExtension extends MessageProcessor {

    public static final String NAME = "IterableExtension";
    public static final String SETTING_API_KEY = "apiKey";
    public static final String SETTING_LIST_ID = "listId";
    private IterableService iterableService;

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
            boolean sandboxed = ((IosRuntimeEnvironment) event.getContext().getRuntimeEnvironment()).getIsSandboxed();
            if (sandboxed) {
                request.device.platform = Device.PLATFORM_APNS_SANDBOX;
            } else {
                request.device.platform = Device.PLATFORM_APNS;
            }
        } else if (event.getContext().getRuntimeEnvironment().getType().equals(RuntimeEnvironment.Type.ANDROID)) {
            request.device.platform = Device.PLATFORM_GCM;
        } else {
            throw new IOException("Cannot process push subscription event for unknown RuntimeEnvironment type.");
        }

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

    private void updateUser(Event.Context context) throws IOException {
        List<UserIdentity> identities = context.getUserIdentities();
        UserUpdateRequest userUpdateRequest = new UserUpdateRequest();
        for (UserIdentity identity : identities) {
            if (identity.getType().equals(UserIdentity.Type.EMAIL)) {
                userUpdateRequest.email = identity.getValue();
            } else if (identity.getType().equals(UserIdentity.Type.CUSTOMER)) {
                userUpdateRequest.userId = identity.getValue();
            }
        }
        if (userUpdateRequest.email != null || userUpdateRequest.userId != null) {
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

    @Override
    public void processUserAttributeChangeEvent(UserAttributeChangeEvent event) throws IOException {
        //there's no reason to do this - it's already done at the start of batch processing
        //updateUser(event.getContext());
    }

    @Override
    public ModuleRegistrationResponse processRegistrationRequest(ModuleRegistrationRequest request) {
        ModuleRegistrationResponse response = new ModuleRegistrationResponse(NAME, "1.0");
        Permissions permissions = new Permissions();
        List<UserIdentityPermission> userIds = Arrays.asList(
                new UserIdentityPermission(UserIdentity.Type.EMAIL, Identity.Encoding.RAW),
                new UserIdentityPermission(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW)
        );

        permissions.setUserIdentities(userIds);
        response.setPermissions(permissions);

        // Register a mobile event stream listener
        EventProcessingRegistration eventProcessingRegistration = new EventProcessingRegistration();
        eventProcessingRegistration.setDescription("Iterable Event Processor");

        // Add account settings that should be provided by the subscribers, such as an API key
        List<Setting> accountSettings = new ArrayList<>();
        TextSetting apiKey = new TextSetting(SETTING_API_KEY, "API Key");
        apiKey.setIsRequired(true);
        accountSettings.add(apiKey);

        eventProcessingRegistration.setAccountSettings(accountSettings);

        // Specify supported event types
        List<Event.Type> supportedEventTypes = Arrays.asList(
                Event.Type.CUSTOM_EVENT,
                Event.Type.PUSH_SUBSCRIPTION,
                Event.Type.PUSH_MESSAGE_RECEIPT,
                Event.Type.USER_ATTRIBUTE_CHANGE,
                Event.Type.USER_IDENTITY_CHANGE);
        eventProcessingRegistration.setSupportedEventTypes(supportedEventTypes);
        response.setEventProcessingRegistration(eventProcessingRegistration);

        AudienceProcessingRegistration audienceRegistration = new AudienceProcessingRegistration();
        audienceRegistration.setDescription("Iterable Segmentation Processor");
        audienceRegistration.setAccountSettings(accountSettings);
        List<Setting> subscriptionSettings = new LinkedList<>();
        subscriptionSettings.add(new IntegerSetting(SETTING_LIST_ID, "List ID"));
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
                request.campaignId = iterableObject.getInt("campaignId");
                request.templateId = iterableObject.getInt("templateId");
                request.createdAt = (int) (event.getTimestamp() / 1000.0);
                Response<IterableApiResponse> response = iterableService.trackPushOpen(request).execute();
                if (response.isSuccess() && !response.body().isSuccess()) {
                    throw new IOException(response.body().toString());
                } else if (!response.isSuccess()) {
                    throw new IOException("Error sending push-open to Iterable: HTTP " + response.code());
                }
            }
        }catch (JSONException jse) {
            throw new IOException(jse);
        }
    }

    @Override
    public AudienceMembershipChangeResponse processAudienceMembershipChangeRequest(AudienceMembershipChangeRequest request) throws IOException {
        Map<String, String> settings = request.getAccount().getAccountSettings();
        String apiKey = settings.get(SETTING_API_KEY);
        IterableService audienceIterableService = IterableService.newInstance(apiKey);
        List<AudienceMembershipChange> membershipChanges = request.getMembershipChanges();

        for (int i = 0; i < membershipChanges.size(); i++) {
            AudienceMembershipChange change = membershipChanges.get(i);
            Map<String, String> audienceSettings = change.getAudienceSubscriptionSettings();
            int listId = Integer.parseInt(audienceSettings.get(SETTING_LIST_ID));

            SubscribeRequest subscribeRequest = new SubscribeRequest();
            subscribeRequest.listId = listId;
            subscribeRequest.subscribers = change.getAddedUserIdentities().stream()
                    .filter(t -> t.getType().equals(UserIdentity.Type.EMAIL))
                    .map(identity -> {
                        ApiUser user = new ApiUser();
                        user.email = identity.getValue();
                        return user;
                    }).collect(Collectors.toList());
            audienceIterableService.listSubscribe(subscribeRequest).execute();

            UnsubscribeRequest unsubscribeRequest = new UnsubscribeRequest();
            unsubscribeRequest.listId = listId;
            unsubscribeRequest.subscribers = change.getRemovedUserIdentities().stream()
                    .filter(t -> t.getType().equals(UserIdentity.Type.EMAIL))
                    .map(identity -> {
                        Unsubscriber user = new Unsubscriber();
                        user.email = identity.getValue();
                        return user;
                    }).collect(Collectors.toList());
            audienceIterableService.listUnsubscribe(unsubscribeRequest).execute();

        }
        //TODO does this response matter?
        return new AudienceMembershipChangeResponse();
    }

}