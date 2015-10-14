package com.mparticle.iterable;

import com.squareup.okhttp.HttpUrl;
import retrofit.Call;
import retrofit.GsonConverterFactory;
import retrofit.Retrofit;
import retrofit.http.Body;
import retrofit.http.POST;

/**
 * Iterable API defined here:
 *
 * https://api.iterable.com/api/docs
 *
 */
public interface IterableService {

    String HOST = "api.iterable.com";
    String PARAM_API_KEY = "api_key";

    @POST("api/events/track")
    Call<IterableApiResponse> track(@Body TrackRequest trackRequest);

    @POST("api/events/trackPushOpen")
    Call<IterableApiResponse> trackPushOpen(@Body TrackPushOpenRequest registerRequest);

    @POST("api/users/update")
    Call<IterableApiResponse> userUpdate(@Body UserUpdateRequest trackRequest);

    @POST("api/users/registerDeviceToken")
    Call<IterableApiResponse> registerToken(@Body RegisterDeviceTokenRequest registerRequest);

    @POST("api/lists/subscribe")
    Call<ListResponse> listSubscribe(@Body SubscribeRequest subscribeRequest);

    @POST("api/lists/unsubscribe")
    Call<ListResponse> listUnsubscribe(@Body UnsubscribeRequest unsubscribeRequest);

    static IterableService newInstance(String apiKey) {
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host(IterableService.HOST)
                .addQueryParameter(IterableService.PARAM_API_KEY, apiKey)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        return retrofit.create(IterableService.class);
    }

}
