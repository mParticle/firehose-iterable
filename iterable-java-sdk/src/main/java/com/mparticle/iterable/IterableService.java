package com.mparticle.iterable;

import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Response;
import retrofit.Call;
import retrofit.GsonConverterFactory;
import retrofit.Retrofit;
import retrofit.http.Body;
import retrofit.http.GET;
import retrofit.http.POST;

import java.io.IOException;

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

    @POST("api/commerce/trackPurchase")
    Call<IterableApiResponse> trackPurchase(@Body TrackPurchaseRequest purchaseRequest);

    /**
     * At the moment this is only used for unit testing the list subscribe/unsubscribe API calls
     */
    @GET("api/lists")
    Call<GetListResponse> lists();

    static IterableService newInstance(String apiKey) {
        //all of this intercepter/chain stuff is just so callers don't
        //have to pass the API key into every single method/api call
        OkHttpClient client = new OkHttpClient();
        client.interceptors().add(
                chain -> chain.proceed(chain.request().newBuilder().url(
                                chain.request()
                                .httpUrl()
                                .newBuilder()
                                .addQueryParameter(IterableService.PARAM_API_KEY, apiKey)
                                .build()
                ).build()
        ));
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host(IterableService.HOST)
                .addQueryParameter(IterableService.PARAM_API_KEY, apiKey)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        return retrofit.create(IterableService.class);
    }

}
