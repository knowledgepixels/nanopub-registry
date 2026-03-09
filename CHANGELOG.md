## [1.1.1](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.1.0...nanopub-registry-1.1.1) (2026-03-09)

### Bug Fixes

* **settings:** update build configuration for setting.trig file ([2c61baa](https://github.com/knowledgepixels/nanopub-registry/commit/2c61baa30ed002f6c2feef54478759328de1e704))

## [1.1.0](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.0.0...nanopub-registry-1.1.0) (2026-03-09)

### Features

* add /debug/peerState endpoint to expose peer sync state ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([149c21d](https://github.com/knowledgepixels/nanopub-registry/commit/149c21d55608f5fb9b36afbc442219c3c3f216b6))
* add abstraction for environment variable handling ([4c129b0](https://github.com/knowledgepixels/nanopub-registry/commit/4c129b0779f5119ecfaade13a78110c98bca79ca))
* add progress logging to full fetch every 1000 nanopubs ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([1337e1c](https://github.com/knowledgepixels/nanopub-registry/commit/1337e1cfbb699726c5d6964bcbf20a20e55b26b6))
* add registry-to-registry peer sync for CHECK_NEW ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([a649fd9](https://github.com/knowledgepixels/nanopub-registry/commit/a649fd9bdd8ead4c80756425454809443843c723))
* add registry-to-registry peer sync for CHECK_NEW ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([17fb949](https://github.com/knowledgepixels/nanopub-registry/commit/17fb9497f0eb0f9fed1f2626d1022acabedd35e6))
* **RegistryDB:** add port configuration for MongoClient initialization ([bc5d0e4](https://github.com/knowledgepixels/nanopub-registry/commit/bc5d0e43afcc5016839a6f58fb38015e72f5a80b))
* reject nanopubs with future timestamps ([#73](https://github.com/knowledgepixels/nanopub-registry/issues/73)) ([5311bd0](https://github.com/knowledgepixels/nanopub-registry/commit/5311bd087a1a4df100b0359b097523609e08e8c1))
* reject nanopubs with graph URIs not matching base URI ([#71](https://github.com/knowledgepixels/nanopub-registry/issues/71)) ([630504a](https://github.com/knowledgepixels/nanopub-registry/commit/630504a22fada135bd46ce20851c5ed8d9642062))
* resumable full fetch using afterCounter position tracking ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([dea0d5c](https://github.com/knowledgepixels/nanopub-registry/commit/dea0d5c7e019a61d5a9200d59b6a4022b903dfbd))

### Dependency updates

* **api-deps:** update org.nanopub:nanopub dependency to v1.86.0 ([0f84881](https://github.com/knowledgepixels/nanopub-registry/commit/0f848819cc2e9b2b1807cccde3d4ba7246f9ecde))
* **core-deps:** update com.google.code.gson:gson dependency to v2.13.2 ([1d8000f](https://github.com/knowledgepixels/nanopub-registry/commit/1d8000f9b7fb7b01326db2770489d4977b111ebf))
* **core-deps:** update eu.neverblink.jelly:jelly-core-protos-google dependency to v3.6.2 ([7ba74e7](https://github.com/knowledgepixels/nanopub-registry/commit/7ba74e73b92ffd52826252879493ce49ccf069d8))
* **core-deps:** update eu.neverblink.jelly:jelly-rdf4j dependency to v3.6.2 ([4fb476d](https://github.com/knowledgepixels/nanopub-registry/commit/4fb476d8651543e2fc9b757c79b8b4a88cbe7bae))
* **core-deps:** update org.nanopub:nanopub dependency to v1.85 ([95a3d07](https://github.com/knowledgepixels/nanopub-registry/commit/95a3d07af1ca3a302a24f5ec89c021362c7e881f))

### Bug Fixes

* Add local.Dockerfile and adjust run.sh ([4648aa8](https://github.com/knowledgepixels/nanopub-registry/commit/4648aa8c495ea046555feae20d3572bea07797cd))
* Adjust Dockerfile and run.sh script ([7b5987e](https://github.com/knowledgepixels/nanopub-registry/commit/7b5987edb56df4be23df137af6d7693a263fc2df))
* allow LOAD_FULL to run during updating status ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([35b7a41](https://github.com/knowledgepixels/nanopub-registry/commit/35b7a4148fa56ab4d9a9e95bbedeb09cae4301e0))
* disable transaction for CHECK_NEW to avoid timeout during peer sync ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([857cb38](https://github.com/knowledgepixels/nanopub-registry/commit/857cb3897196acb7ca7410afba2125282395a6fc))
* Fix of the fix; this is to avoid NullPointerExceptions ([97f43a2](https://github.com/knowledgepixels/nanopub-registry/commit/97f43a27de8e623717d3adfa653064eaa862f73c))
* handle duplicate key race in simpleLoad and discoverPubkeys ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([8fd8fab](https://github.com/knowledgepixels/nanopub-registry/commit/8fd8fabbc80541dca453791212bdf064b0ce469e))
* handle null Accept header in getMimeType ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([797b85d](https://github.com/knowledgepixels/nanopub-registry/commit/797b85d7a96d0a6cc8340a54225d0bc401b981ea))
* only set fullFetchDone when full fetch succeeds ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([3d112e0](https://github.com/knowledgepixels/nanopub-registry/commit/3d112e0b0e8af12818e8c110cc327b0a193cd0f3))
* Properly configure logging to make it work again ([e61458a](https://github.com/knowledgepixels/nanopub-registry/commit/e61458aaac295d921af0ef57210889644c17d032))
* set content type before writing body in /debug/peerState ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([89a6396](https://github.com/knowledgepixels/nanopub-registry/commit/89a63962ad53b573383b7e26f2ff77dd18595d75))
* skip seen accounts without trust paths instead of aborting depth ([#76](https://github.com/knowledgepixels/nanopub-registry/issues/76)) ([0d04dc8](https://github.com/knowledgepixels/nanopub-registry/commit/0d04dc8f2a3e08226467a76285467b72ee15b5ab))
* store nanopubs unconditionally during peer sync ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([c225d95](https://github.com/knowledgepixels/nanopub-registry/commit/c225d953a18c1e67bf26f5131712c2151762b457))
* use dedicated session for full fetch to avoid transaction timeout ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([0ab3f02](https://github.com/knowledgepixels/nanopub-registry/commit/0ab3f0230a0e88fa90d5f20c8b9bcde97b679b48))
* use untyped getters in /debug/peerState to avoid ClassCastException ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([c5b4fb8](https://github.com/knowledgepixels/nanopub-registry/commit/c5b4fb8b7bbd0a8706428cb4224e42ddf240eaf6))
* **Utils:** make NanopubSetting volatile to ensure thread safety ([a2bec05](https://github.com/knowledgepixels/nanopub-registry/commit/a2bec05d28a7bb0d6ef48d8752bc74460ba8952b))
* Values loaded from DB are not necessarily strings ([8d48ad9](https://github.com/knowledgepixels/nanopub-registry/commit/8d48ad909c2aebd427ca962036fe01de2bdea0b4))

### Documentation

* add HTTP API, task workflow, and jelly field to design.md ([e314bac](https://github.com/knowledgepixels/nanopub-registry/commit/e314bacab0740fa67af128f3c95917f8f7ad5f48))
* add package-info for database interaction management package ([ba52690](https://github.com/knowledgepixels/nanopub-registry/commit/ba52690774d74d25bf46a16a6d49f43681096f47))
* align design.md data structure with actual implementation ([725d7a6](https://github.com/knowledgepixels/nanopub-registry/commit/725d7a6876cde31e6fc689e629d5a27d085c1fbf))
* **BufferOutputStream:** add Javadoc ([b904753](https://github.com/knowledgepixels/nanopub-registry/commit/b90475334b361ef58a7eedc0ecba208433d30b0b))
* convert kebab-case to camelCase in design.md ([b24aab6](https://github.com/knowledgepixels/nanopub-registry/commit/b24aab6837591ca801e2158163fb8c5b514c9148))
* **DbEntryWrapper:** add missing Javadoc annotations ([c07d663](https://github.com/knowledgepixels/nanopub-registry/commit/c07d6630a5a89173f4de985d5ce4d9b82812f47a))
* fix remaining inconsistencies in design.md ([7eac75f](https://github.com/knowledgepixels/nanopub-registry/commit/7eac75f42c3950ec1bf95d5653ac5bf78256552f))
* **Page:** add missing Javadoc annotations ([00e1c8b](https://github.com/knowledgepixels/nanopub-registry/commit/00e1c8b619212c55dc30150e83853b226cc87850))
* **RegistryDB:** add Javadoc annotations (also not related but improved logging messages) ([b51de8c](https://github.com/knowledgepixels/nanopub-registry/commit/b51de8c99203362184083acf3276a0c025a8116e))
* update design.md for HEAD-based peer sync and task workflow ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([f95c79a](https://github.com/knowledgepixels/nanopub-registry/commit/f95c79a07a6ab272cfdd76f309f7bba82e9ddd4b))
* update serverInfo fields in design.md ([9f2d16e](https://github.com/knowledgepixels/nanopub-registry/commit/9f2d16e1d8b755207069c602f1feb3f9464548de))
* **Utils:** add missing Javadoc annotations ([3565770](https://github.com/knowledgepixels/nanopub-registry/commit/35657707516f7e1c0f599214b6aaf9689e47f4a4))

### Performance improvements

* reduce log noise by demoting Already loaded/listed to debug ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([71b1626](https://github.com/knowledgepixels/nanopub-registry/commit/71b1626a2df67b77fa60be95421420cad7355689))

### Tests

* add testsuite submodule ([2333456](https://github.com/knowledgepixels/nanopub-registry/commit/23334569f730d8fbba95d716346b5c4fab5393bf))
* **AgentInfo:** add unit tests ([7af0b14](https://github.com/knowledgepixels/nanopub-registry/commit/7af0b149d99ff2776131e5e3e3dfb48ec239ecef))
* **ApplicationLauncher:** add unit test for metrics options initialization ([f3ce1a9](https://github.com/knowledgepixels/nanopub-registry/commit/f3ce1a93640f7a6708f1b0bc600b5a67e67afbce))
* **BufferOutputStream:** add unit tests ([309a64e](https://github.com/knowledgepixels/nanopub-registry/commit/309a64e6a575453e52f66cd1a6c8bd8e61376af9))
* **DbEntryWrapper:** add unit tests ([b8515d2](https://github.com/knowledgepixels/nanopub-registry/commit/b8515d2eadd7ef2959ca95f50ae8dbb12747fb90))
* **deps:** add org.mockito:mockito-core dependency v5.21.0 ([714076f](https://github.com/knowledgepixels/nanopub-registry/commit/714076f3d81f57678eb3a97c7aaddbc802d8d97d))
* **deps:** add org.testcontainers:testcontainers and org.testcontainers:testcontainers-junit-jupiter dependencies to v2.0.1 ([8c6816f](https://github.com/knowledgepixels/nanopub-registry/commit/8c6816f2d7894e3f5065b34b0beb225436d23af8))
* **deps:** add org.testcontainers:testcontainers-mongodb dependency to v2.0.1 ([12759fa](https://github.com/knowledgepixels/nanopub-registry/commit/12759fac00591c48fa2ad72d7f06139cd9d150c1))
* **EntryStatus:** add unit tests ([b9c89c4](https://github.com/knowledgepixels/nanopub-registry/commit/b9c89c41498b3905a4b01ce58b8cb0e06ca16329))
* **exceptions:** add unit tests ([5db1930](https://github.com/knowledgepixels/nanopub-registry/commit/5db19300f6d230234e877bf4a7d4535674f70a3b))
* Fix test ([fbb8ca8](https://github.com/knowledgepixels/nanopub-registry/commit/fbb8ca8b58131e2483f26b310e8fdda7a1c23b43))
* **IndexInitializer:** move related core from RegistryDB into the new test class ([a30cc0e](https://github.com/knowledgepixels/nanopub-registry/commit/a30cc0e51c154f7c01e10d9ae445b888f911997a))
* **Page:** add unit tests ([727c2da](https://github.com/knowledgepixels/nanopub-registry/commit/727c2daafc7e8570a3103e673e19e6ab2fa383f4))
* **RegistryDB:** add unit tests for collection rename and value retrieval methods ([31defd9](https://github.com/knowledgepixels/nanopub-registry/commit/31defd99a00fbcbae128c5013a6b3cb0bb4ea48b))
* **RegistryDB:** add unit tests for database operations and state management ([49ee0d4](https://github.com/knowledgepixels/nanopub-registry/commit/49ee0d454555b095ada4fcf663a8da5a04e35513))
* **RegistryDB:** add unit tests for getPubkey method with valid and invalid nanopubs ([4c7424c](https://github.com/knowledgepixels/nanopub-registry/commit/4c7424cb50e5b6987cd6e0e7d3cd479577b0888b))
* **RegistryDB:** add unit tests for uninitialized database and client retrieval ([86f2347](https://github.com/knowledgepixels/nanopub-registry/commit/86f2347550ecd70ad118a1ee114b5fd1f609b60c))
* **RegistryDB:** enhance unit tests for MongoDB client/DB retrieval and add integration test for MongoDB initialization using testcontainers ([25742fb](https://github.com/knowledgepixels/nanopub-registry/commit/25742fbaa3022ac59af5e232d4769bcead0d72cf))
* **RegistryDBTest:** remove old initLoadingCollections test method ([b7afd80](https://github.com/knowledgepixels/nanopub-registry/commit/b7afd80c8749aaf24550534a70893d0d668d1c34))
* **Task:** add `loadConfig` unit test and improve setUp method ([f4d2141](https://github.com/knowledgepixels/nanopub-registry/commit/f4d2141a949961efbdf29ee65c8abf052b473c39))
* **Task:** add unit tests for db initialization task ([3fbbdbe](https://github.com/knowledgepixels/nanopub-registry/commit/3fbbdbeff1981c8bf274a83282937fbfb9353123))
* **Task:** enhance task loading tests and fix assertion for retrieved tasks ([e0db478](https://github.com/knowledgepixels/nanopub-registry/commit/e0db478b12da678bd8812d588dbc07d5eb409797))
* **TaskTest:** enhance test setup with fake environment and add `loadSetting` test ([cca4850](https://github.com/knowledgepixels/nanopub-registry/commit/cca485034e35c03ee5cc811651703b449de1b0f6))
* update AgentInfoTest with constants and add RegistryInfoTest and CollectionTest unit tests ([a83b49d](https://github.com/knowledgepixels/nanopub-registry/commit/a83b49da8a2e1e6cd1d0e4e8e51ea524198c616a))
* **Utils:** add unit test for getInvalidatedNanopubIds method ([1192de6](https://github.com/knowledgepixels/nanopub-registry/commit/1192de69b1cc16318498e8fd6223ec19781a6624))
* **Utils:** add unit tests for peer URLs handling ([9ff0684](https://github.com/knowledgepixels/nanopub-registry/commit/9ff0684a59c4edfd70f4f75593311baf96693b5a))
* **Utils:** enhance unit tests for environment variable handling ([eaf2513](https://github.com/knowledgepixels/nanopub-registry/commit/eaf2513324bfa6c335439eebbd8853b53d3c1362))
* **UtilsTest:** add unit tests for utility methods ([4be79b0](https://github.com/knowledgepixels/nanopub-registry/commit/4be79b01161a62c9f2a99a730a6085e27333209d))

### General maintenance

* add command for updating git submodule before testing ([393c52a](https://github.com/knowledgepixels/nanopub-registry/commit/393c52a335420e2644d2714511442de468dad649))
* add contributing guidelines ([bab5acd](https://github.com/knowledgepixels/nanopub-registry/commit/bab5acd5b1fcfa1d1f7bde80e9602070ea0c7425))
* Adjust .gitignore ([b1d0b84](https://github.com/knowledgepixels/nanopub-registry/commit/b1d0b84d5b265a0da943f1c8c1d776aa24dce072))
* **collection:** add Collection enum for registry constants ([533322e](https://github.com/knowledgepixels/nanopub-registry/commit/533322e3d87af76297339ad9a92a75bc5215a316))
* **Collection:** add TASKS constant to Collection enum ([134f04d](https://github.com/knowledgepixels/nanopub-registry/commit/134f04d1859cae32108ca0503c8eca8b3f038547))
* **exceptions:** add custom exception classes ([78a3756](https://github.com/knowledgepixels/nanopub-registry/commit/78a3756c6e2b7ae0408572cbafed1fed6b2d0dca))
* **FakeEnv:** add utility class for creating a fake environment for testing ([d5fc80a](https://github.com/knowledgepixels/nanopub-registry/commit/d5fc80a20aaf1e3a1ce8273a90335f7b97c9cf58))
* Fix typo in variable name ([41952e3](https://github.com/knowledgepixels/nanopub-registry/commit/41952e31d52231f5fdef90385e1210bdca5aca62))
* **logging:** add logging messages for MongoDB index initialization and element retrieval ([74e61b4](https://github.com/knowledgepixels/nanopub-registry/commit/74e61b4dca7771917a7d112a441a49b2ae24bf4b))
* minor code cleanup ([9204b09](https://github.com/knowledgepixels/nanopub-registry/commit/9204b091fabed588508acf28cf24b25b8abfeada))
* **readme:** add semantic-release badge ([1577505](https://github.com/knowledgepixels/nanopub-registry/commit/15775050863dfc792f64576661e219889e28b84c))
* **RegistryDB:** correct logging message and enhance error handling for nanopub signature extraction ([2345e07](https://github.com/knowledgepixels/nanopub-registry/commit/2345e07f21ed7c1a2de67b60e29f831027d19331))
* setting next snapshot version [skip ci] ([1dc16e0](https://github.com/knowledgepixels/nanopub-registry/commit/1dc16e0bb9a9c01b5871160a1864e5f10ad3fc37))
* **TestUtils:** add method to clear specified static fields with given values ([fb8e11d](https://github.com/knowledgepixels/nanopub-registry/commit/fb8e11d3cd3e32230e0b2ecc267be2d67eef4f82))
* **TestUtils:** add methods for managing temporary data directory in tests ([e6c45fb](https://github.com/knowledgepixels/nanopub-registry/commit/e6c45fb3a036f401b6f5f14c9f1452ef05ce704f))
* **TestUtils:** add utility class for test environment setup and static field clearing ([271be82](https://github.com/knowledgepixels/nanopub-registry/commit/271be82f0a3a96bf9119f704f3adae4e31ca0ed6))
* **Utils:** enhance environment variable retrieval with logging messages ([7350f28](https://github.com/knowledgepixels/nanopub-registry/commit/7350f282248f9367f42e0a7e8b90dbb5f0a29e1d))

### Refactoring

* add IndexInitializer class for MongoDB index/collections management ([b6d4c0f](https://github.com/knowledgepixels/nanopub-registry/commit/b6d4c0f98ba17bdd7cbcb68d9b0751b3d7a2c8af))
* **EntryStatus:** improve error message in fromValue method ([4ca3f6c](https://github.com/knowledgepixels/nanopub-registry/commit/4ca3f6c87baf664a32f14bf6858ba0051a1a21b1))
* **exceptions:** replace RuntimeExceptions with custom exception classes ([3dcb8cc](https://github.com/knowledgepixels/nanopub-registry/commit/3dcb8cc6c17495afae77cf3994cb4f4748f3c549))
* **logging:** replace System.err with SLF4J logger for improved logging consistency ([200802c](https://github.com/knowledgepixels/nanopub-registry/commit/200802c8ce64865722f0e5ce9245d6e3d21429f5))
* **MainVerticle:** streamline route handlers and improve logging ([910bb0a](https://github.com/knowledgepixels/nanopub-registry/commit/910bb0a4cfa87b4ed68d40b6e4834006cef34ea9))
* **RegistryDB:** streamline environment variable retrieval to ease testing ([402ec4e](https://github.com/knowledgepixels/nanopub-registry/commit/402ec4ecc6b899cf8686c9c1feeced431d66479c))
* remove loadByApprovedPubkeys in favor of unified incremental sync ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([015cbfd](https://github.com/knowledgepixels/nanopub-registry/commit/015cbfd3e9b5ff062c161dc10b90d2ae901179c7))
* replace string literals with Collection enum constants ([dcdc7e0](https://github.com/knowledgepixels/nanopub-registry/commit/dcdc7e0d808ab65627e164b225a1d3ea10e941c1))
* replace string literals with Collection enum constants ([4f4d7d9](https://github.com/knowledgepixels/nanopub-registry/commit/4f4d7d9a25030dd175dc760feb7e7ef302f305cf))
* **TaskTest, UtilsTest:** replace FileUtils with TestUtils for data directory management ([8a78c3d](https://github.com/knowledgepixels/nanopub-registry/commit/8a78c3da1589a9869094d52707a7b52d33b773cb))
* **tests:** improve fake environment handling in test setup and teardown ([ec9da06](https://github.com/knowledgepixels/nanopub-registry/commit/ec9da069664f6d255c7ded21a54ebca4167c6d7b))
* **tests:** simplify environment setup and clear static fields in test classes ([c0435df](https://github.com/knowledgepixels/nanopub-registry/commit/c0435df31da3ce4f21aebbde5789a1c6aa0ef7fe))
* **TestUtils:** enhance fake environment setup and add database variable configuration ([81d4509](https://github.com/knowledgepixels/nanopub-registry/commit/81d4509bc725ff246681eeacdb30dbbb1ac24890))
* update NanopubLoader and Utils to use NPX vocabulary ([427c87a](https://github.com/knowledgepixels/nanopub-registry/commit/427c87a5dcc0d4803d18087a53c5948cf72bf5b6))
* use ErrorCategory.DUPLICATE_KEY instead of magic number ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([e7b5435](https://github.com/knowledgepixels/nanopub-registry/commit/e7b5435a02869ced2d9f7dea29759e074ce28694))
* use HEAD requests instead of JSON API for peer checks ([#74](https://github.com/knowledgepixels/nanopub-registry/issues/74)) ([5380616](https://github.com/knowledgepixels/nanopub-registry/commit/53806161b131954d65b62621be9935bfbe132c54))
* **Utils:** replace string literals with EntryStatus enum constants ([542aab8](https://github.com/knowledgepixels/nanopub-registry/commit/542aab84e56f8bb5250dc0dfcbf9cb8172af9b9c))
* **UtilsTest:** streamline static field clearing in setup method ([de2d1e6](https://github.com/knowledgepixels/nanopub-registry/commit/de2d1e6875e0670a88353fc03c6dadcf153d15f7))

## 1.0.0 (2025-12-19)

### Features

* Update to new setting ([a08be08](https://github.com/knowledgepixels/nanopub-registry/commit/a08be087e53dc41c85abc75502d8eb958f0fda6b))

### Dependency updates

* **deps:** add org.jacoco:jacoco-maven-plugin dependency to v0.8.13 ([6f2ccc3](https://github.com/knowledgepixels/nanopub-registry/commit/6f2ccc39dfcfccf0c9e3cf1801a4a75cc787e98e))
* **deps:** update dependency maven to v3.9.12 ([55f909f](https://github.com/knowledgepixels/nanopub-registry/commit/55f909f4af047c6d3257ec1b650805d41c55eae9))

### Bug Fixes

* **MainVerticle:** POST requests with invalid nanopubs properly fail ([b0b0e76](https://github.com/knowledgepixels/nanopub-registry/commit/b0b0e768f5213b9433ebe0e13089d7787fcc4378))
* Remaining change for previous fix ([451adb7](https://github.com/knowledgepixels/nanopub-registry/commit/451adb774cb03845eded5e8a8b621cacb7edf845))
* validate agent ID in getAgentLabel method and clean up code formatting ([8104acc](https://github.com/knowledgepixels/nanopub-registry/commit/8104acc20566af0a4c92b2dc07e5cbef3bf6ae33))

### Tests

* add unit tests for Utils methods ([10175b1](https://github.com/knowledgepixels/nanopub-registry/commit/10175b17ad1faa245149a7456cc21b682544c16f))

### Build and continuous integration

* add test and release workflows ([eeb2392](https://github.com/knowledgepixels/nanopub-registry/commit/eeb2392736b917a99e30cd6ef4db87e930ce6195))
* **deps:** add semantic-release dependencies ([d6c5329](https://github.com/knowledgepixels/nanopub-registry/commit/d6c532998bd48791c6a7ccacd0967f8cb3d66042))
* **deps:** update com.google.cloud.tools:jib-maven-plugin dependency to v3.5.1 ([b8a486a](https://github.com/knowledgepixels/nanopub-registry/commit/b8a486a067f3049cdc288186b2f7327569bc0b58))

### General maintenance

* add Maven settings for Docker registry authentication ([81c53d5](https://github.com/knowledgepixels/nanopub-registry/commit/81c53d5be7248f19b9ad1b37fc77935330e306da))
* add Maven wrapper ([081dc0a](https://github.com/knowledgepixels/nanopub-registry/commit/081dc0ae7a7a82cc5d253bbbfcf3cc0da6988fa2))
* **gitignore:** add intellij project files ([5132053](https://github.com/knowledgepixels/nanopub-registry/commit/5132053c472709921f410c850e75a9cc36d134c4))
* **gitignore:** remove Eclipse settings files ([aa4f731](https://github.com/knowledgepixels/nanopub-registry/commit/aa4f7313e4a73d0e00af84c095599622de3b9ea4))
* **readme:** add coverage status badge ([bf58285](https://github.com/knowledgepixels/nanopub-registry/commit/bf58285b85a12676d8c0ccbdcc2c088e34ffb7a6))
* repo cleaning and minor changes ([bc71330](https://github.com/knowledgepixels/nanopub-registry/commit/bc71330da636ada80e809d00c63884cd835f2663))
* **sem-release:** add configuration ([f9c4730](https://github.com/knowledgepixels/nanopub-registry/commit/f9c47302fef3464eea6e8af94c798b51a2d221cb))
* Update .gitignore ([69d0c37](https://github.com/knowledgepixels/nanopub-registry/commit/69d0c37f320504c4cdbd9824f89ea77562609d95))
* update version to 1.0.0-SNAPSHOT to comply with semantic versioning ([4b8c668](https://github.com/knowledgepixels/nanopub-registry/commit/4b8c668510abde7cfd55a81dcb20e37c4f7cb304))

### Refactoring

* replace tabs with spaces ([92fb0da](https://github.com/knowledgepixels/nanopub-registry/commit/92fb0da070502c953a72e6d7559dade0cf67c834))
