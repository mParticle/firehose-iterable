# mParticle / Iterable Extension

This library is meant to be used with Amazon AWS's Lambda platform to facilitate the integration between mParticle and the Iterable API.

The extension is composed of two modules:

- `iterable-extension` - this houses the lambda function that will be executed by AWS on reception of a data request from mParticle
- `iterable-java-sdk` - this is a series of classes modeling Iterable data-structures, and a Retrofit-based interface to their API
