# mParticle - Iterable Extension

This library integrates mParticle with [Iterable](https://www.iterable.com/), using [mParticle's Java SDK](https://github.com/mParticle/mparticle-sdk-java) over Amazon's [Lambda platform](https://aws.amazon.com/lambda/). 

The extension is composed of two modules:

- `iterable-extension` - houses the lambda function that will be executed by AWS on reception of a data request from mParticle. 
- `iterable-java-sdk` - classes modeling Iterable data-structures, and a [Retrofit-based](https://github.com/square/retrofit) interface to their API.

## Testing

Both modules have a set of unit tests which can all be run at once by invoking:

    ./gradlew test

## Building

Run the following to generate `iterable-extension.zip` in the `iterable-extension/build/distributions` directory:

    ./gradlew build
    
## License

[Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0)
