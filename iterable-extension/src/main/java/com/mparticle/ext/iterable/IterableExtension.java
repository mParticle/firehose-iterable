package com.mparticle.ext.iterable;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.mparticle.iterable.*;
import com.mparticle.sdk.MessageProcessor;
import com.mparticle.sdk.model.audienceprocessing.Audience;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeRequest;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeResponse;
import com.mparticle.sdk.model.audienceprocessing.UserProfile;
import com.mparticle.sdk.model.eventprocessing.*;
import com.mparticle.sdk.model.registration.*;
import retrofit.Response;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        Collections.sort(
                request.getEvents(),
                (a, b) -> a.getTimestamp() > b.getTimestamp() ? 1 : a.getTimestamp() == b.getTimestamp() ? 0 : -1
        );
        insertPlaceholderEmail(request);
        updateUser(request);
        return super.processEventProcessingRequest(request);
    }

    /**
     * Verify that there's an email present, create a placeholder if not.
     *
     * @param request
     * @throws IOException
     */
    private void insertPlaceholderEmail(EventProcessingRequest request) throws IOException {
        long count = request.getUserIdentities() == null ? 0 : request.getUserIdentities().stream()
                .filter(t -> t.getType().equals(UserIdentity.Type.EMAIL))
                .count();
        if (count > 0) {
            return;
        }
        String placeholderEmail = getPlaceholderEmail(request);
        if (request.getUserIdentities() == null) {
            request.setUserIdentities(new ArrayList<>());
        }
        request.getUserIdentities().add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, placeholderEmail));
    }

    @Override
    public void processPushSubscriptionEvent(PushSubscriptionEvent event) throws IOException {
        RegisterDeviceTokenRequest request = new RegisterDeviceTokenRequest();
        if (PushSubscriptionEvent.Action.UNSUBSCRIBE.equals(event.getAction())) {
            return;
        }
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
            UserIdentity email = event.getContext().getUserIdentities().stream()
                    .filter(t -> t.getType().equals(UserIdentity.Type.EMAIL))
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

    void updateUser(EventProcessingRequest request) throws IOException {
        Event.Context context = new Event.Context(request);

        if (request.getEvents() != null) {
            Stream<UserIdentityChangeEvent> stream = request.getEvents().stream()
                    .filter(e -> e.getType() == Event.Type.USER_IDENTITY_CHANGE)
                    .map(e -> (UserIdentityChangeEvent) e);

            List<UserIdentityChangeEvent> emailChangeEvents = stream.filter(e -> e.getAdded() != null && e.getRemoved() != null)
                    .filter(e -> e.getAdded().size() > 0 && e.getRemoved().size() > 0)
                    .filter(e -> e.getAdded().get(0).getType().equals(UserIdentity.Type.EMAIL))
                    .filter(e -> !isEmpty(e.getAdded().get(0).getValue()) && !isEmpty(e.getRemoved().get(0).getValue()))
                    .collect(Collectors.toList());

            List<UserIdentityChangeEvent> emailAddedEvents = stream.filter(e -> e.getAdded() != null && e.getAdded().size() > 0)
                    .filter(e -> e.getRemoved() == null || e.getRemoved().size() == 0)
                    .filter(e -> e.getAdded().get(0).getType().equals(UserIdentity.Type.EMAIL))
                    .filter(e -> !isEmpty(e.getAdded().get(0).getValue()))
                    .collect(Collectors.toList());

            String placeholderEmail = getPlaceholderEmail(request);
            //convert from placeholder to email now that we have one
            for (UserIdentityChangeEvent changeEvent : emailAddedEvents) {

                UpdateEmailRequest updateEmailRequest = new UpdateEmailRequest();
                updateEmailRequest.currentEmail = placeholderEmail;
                //this is safe due to the filters above
                updateEmailRequest.newEmail = changeEvent.getAdded().get(0).getValue();
                Response<IterableApiResponse> response = iterableService.updateEmail(updateEmailRequest).execute();
                if (response.isSuccess()) {
                    IterableApiResponse apiResponse = response.body();
                    if (apiResponse != null && !apiResponse.isSuccess()) {
                        throw new IOException("Error while calling updateEmail() on iterable: HTTP " + apiResponse.code);
                    }
                }
            }

            //convert from old to new email
            for (UserIdentityChangeEvent changeEvent : emailChangeEvents) {
                UpdateEmailRequest updateEmailRequest = new UpdateEmailRequest();
                //these are safe due to the filters above
                updateEmailRequest.currentEmail = changeEvent.getRemoved().get(0).getValue();
                updateEmailRequest.newEmail = changeEvent.getAdded().get(0).getValue();
                Response<IterableApiResponse> response = iterableService.updateEmail(updateEmailRequest).execute();
                if (response.isSuccess()) {
                    IterableApiResponse apiResponse = response.body();
                    if (apiResponse != null && !apiResponse.isSuccess()) {
                        throw new IOException("Error while calling updateEmail() on iterable: HTTP " + apiResponse.code);
                    }
                }
            }
        }

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
            if (!isEmpty(userUpdateRequest.email) || !isEmpty(userUpdateRequest.userId)) {
                userUpdateRequest.dataFields = context.getUserAttributes();
                Response<IterableApiResponse> response = iterableService.userUpdate(userUpdateRequest).execute();
                if (response.isSuccess()) {
                    IterableApiResponse apiResponse = response.body();
                    if (apiResponse != null && !apiResponse.isSuccess()) {
                        throw new IOException("Error while calling updateUser() on iterable: HTTP " + apiResponse.code);
                    }
                }
            }
        }
    }

    private static boolean isEmpty(CharSequence chars) {
        return chars == null || "".equals(chars);
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

    /**
     * Make the best attempt at creating a placeholder email, prioritize:
     *  1. device application stamp
     *  2. device ID
     *  3. customer Id
     * @param request
     * @return
     */
    private static String getPlaceholderEmail(EventProcessingRequest request) throws IOException {
        // https://support.iterable.com/hc/en-us/articles/208499956-Creating-user-profiles-without-an-email-address
        String id = request.getDeviceApplicationStamp();
        if (isEmpty(id)) {
            if (request.getRuntimeEnvironment() instanceof IosRuntimeEnvironment) {
                DeviceIdentity deviceIdentity = ((IosRuntimeEnvironment)request.getRuntimeEnvironment()).getIdentities() == null ? null : ((IosRuntimeEnvironment)request.getRuntimeEnvironment()).getIdentities().stream().filter(t -> t.getType().equals(DeviceIdentity.Type.IOS_VENDOR_ID))
                        .findFirst()
                        .get();
                if (deviceIdentity != null) {
                    id = deviceIdentity.getValue();
                }
            } else if (request.getRuntimeEnvironment() instanceof TVOSRuntimeEnvironment) {
                DeviceIdentity deviceIdentity = ((TVOSRuntimeEnvironment)request.getRuntimeEnvironment()).getIdentities() == null ? null : ((TVOSRuntimeEnvironment)request.getRuntimeEnvironment()).getIdentities().stream().filter(t -> t.getType().equals(DeviceIdentity.Type.IOS_VENDOR_ID))
                        .findFirst()
                        .get();
                if (deviceIdentity != null) {
                    id = deviceIdentity.getValue();
                }
            } else if (request.getRuntimeEnvironment() instanceof AndroidRuntimeEnvironment) {
                DeviceIdentity deviceIdentity = ((AndroidRuntimeEnvironment)request.getRuntimeEnvironment()).getIdentities() == null ? null : ((AndroidRuntimeEnvironment)request.getRuntimeEnvironment()).getIdentities().stream().filter(t -> t.getType().equals(DeviceIdentity.Type.ANDROID_ID))
                        .findFirst()
                        .get();
                if (deviceIdentity != null) {
                    id = deviceIdentity.getValue();
                }
            }
        }
        if (isEmpty(id)) {
            if (request.getUserIdentities() != null) {
                UserIdentity customerId = request.getUserIdentities().stream()
                        .filter(t -> t.getType().equals(UserIdentity.Type.CUSTOMER))
                        .findFirst()
                        .get();
                if (customerId != null) {
                    id = customerId.getValue();
                }
            }
        }

        if (isEmpty(id)) {
            throw new IOException("Unable to send user to Iterable - no email and unable to construct placeholder.");
        }
        return id + "@placeholder.email";
    }

    @Override
    public ModuleRegistrationResponse processRegistrationRequest(ModuleRegistrationRequest request) {
        ModuleRegistrationResponse response = new ModuleRegistrationResponse(NAME, "1.2");

        Permissions permissions = new Permissions();
        permissions.setUserIdentities(
                Arrays.asList(
                        new UserIdentityPermission(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, false),
                        new UserIdentityPermission(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, false)
                )
        );
        permissions.setDeviceIdentities(
                Arrays.asList(
                        new DeviceIdentityPermission(DeviceIdentity.Type.GOOGLE_CLOUD_MESSAGING_TOKEN, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.APPLE_PUSH_NOTIFICATION_TOKEN, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.IOS_VENDOR_ID, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.ANDROID_ID, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.GOOGLE_ADVERTISING_ID, Identity.Encoding.RAW)
                )
        );
        permissions.setAllowAccessDeviceApplicationStamp(true);
        response.setPermissions(permissions);
        response.setDescription("<a href=\"https://www.iterable.com\">Iterable</a> makes consumer growth marketing and user engagement simple. With Iterable, marketers send the right message, to the right device, at the right time.");
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
        request.dataFields = attemptTypeConversion(event.getAttributes());
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

    /**
     * Make a best-effort attempt to coerce the values of each map item to bool, double, int, and string types
     *
     * mParticle's API only accepts string, whereas Iterable's API accept different types. By coercing these types,
     * users of the Iterable API are able to create campaigns, aggregate events, etc.
     *
     * @param attributes
     * @return
     */
    private Map<String, Object> attemptTypeConversion(Map<String, String> attributes) {
        if (attributes == null) {
            return null;
        }
        Map<String, Object> converted = new HashMap<>(attributes.size());
        attributes.forEach((key,value)-> {
            if (isEmpty(value)) {
                converted.put(key, value);
            } else {
                if (value.toLowerCase(Locale.US).equals("true") || value.toLowerCase(Locale.US).equals("false")) {
                    converted.put(key, Boolean.parseBoolean(value));
                } else {
                    try {
                        double doubleValue = Double.parseDouble(value);
                        if ((doubleValue % 1) == 0) {
                            converted.put(key, Integer.parseInt(value));
                        } else {
                            converted.put(key, doubleValue);
                        }
                    }catch (NumberFormatException nfe) {
                        converted.put(key, value);
                    }
                }
            }

        });
        return converted;
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
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> payload = mapper.readValue(event.getPayload(), Map.class);
            if (payload.containsKey("itbl")) {
                request.campaignId = Integer.parseInt(mapper.writeValueAsString(((Map)payload.get("itbl")).get("campaignId")));
                request.templateId = Integer.parseInt(mapper.writeValueAsString(((Map)payload.get("itbl")).get("templateId")));
                request.createdAt = (int) (event.getTimestamp() / 1000.0);
                Response<IterableApiResponse> response = iterableService.trackPushOpen(request).execute();
                if (response.isSuccess() && !response.body().isSuccess()) {
                    throw new IOException(response.body().toString());
                } else if (!response.isSuccess()) {
                    throw new IOException("Error sending push-open to Iterable: HTTP " + response.code());
                }
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
