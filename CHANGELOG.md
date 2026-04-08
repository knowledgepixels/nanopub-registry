## [1.7.2](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.7.1...nanopub-registry-1.7.2) (2026-04-08)

### Bug Fixes

* use counter field for jelly endpoint compatibility with old nanopubs ([9d5fb00](https://github.com/knowledgepixels/nanopub-registry/commit/9d5fb0017d8012ac7c75bd8ee43a9cde6f9703d4))

### General maintenance

* setting next snapshot version [skip ci] ([1c59dc2](https://github.com/knowledgepixels/nanopub-registry/commit/1c59dc2f2dcb193cbc21e2dd77280b7d0e7a5580))

## [1.7.1](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.7.0...nanopub-registry-1.7.1) (2026-04-08)

### Bug Fixes

* support "all" as value for REGISTRY_COVERAGE_TYPES ([54faf1b](https://github.com/knowledgepixels/nanopub-registry/commit/54faf1be761e210b11b20379e5bff50588a49bce))

### General maintenance

* setting next snapshot version [skip ci] ([e5172c6](https://github.com/knowledgepixels/nanopub-registry/commit/e5172c61a5a0a35759ebeaafb692950ab4f54e29))

## [1.7.0](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.6.0...nanopub-registry-1.7.0) (2026-04-08)

### Features

* add capped status for quota-reached accounts, store effective quota ([57be4d5](https://github.com/knowledgepixels/nanopub-registry/commit/57be4d5ff573e99c5391cd08e90b13da4dda3e37))
* add env vars to disable optional loading and trust calculation ([778a954](https://github.com/knowledgepixels/nanopub-registry/commit/778a954407e354149db397dc7014f5304292bd59))
* enforce agent/pubkey quota restrictions ([aef8d60](https://github.com/knowledgepixels/nanopub-registry/commit/aef8d601d1ee75d72bf8d54edebb4170a0f8b74b))
* enforce type coverage restrictions across all loading paths ([6719d17](https://github.com/knowledgepixels/nanopub-registry/commit/6719d171b4d77e94eecdd9b5c89fda4106327cb3))
* expose optionalLoadEnabled and trustCalculationEnabled in UI and API ([62b475f](https://github.com/knowledgepixels/nanopub-registry/commit/62b475fba5470f2af1a0d1efabca5782112ee5cf))
* make quota constants configurable via env vars ([0d71a50](https://github.com/knowledgepixels/nanopub-registry/commit/0d71a504964b91eb201c931cea3c3195e5c61bfe))
* show nanopub count per account on list pages ([9e9b9b7](https://github.com/knowledgepixels/nanopub-registry/commit/9e9b9b7336b59e05bc53e6be6c24094458446b91))

### Bug Fixes

* disable checksum skip in LOAD_CORE to preserve endorsement extraction ([0be57ff](https://github.com/knowledgepixels/nanopub-registry/commit/0be57ff1eb143768752d477e85ae5141b37eeda9))
* reject whitespace in REGISTRY_COVERAGE_TYPES config ([78e8ebf](https://github.com/knowledgepixels/nanopub-registry/commit/78e8ebf832374fb18eb5ccc25c8a023a356ada8b))
* remove AgentFilter checks from simpleLoad and peer sync loading ([7d30bb9](https://github.com/knowledgepixels/nanopub-registry/commit/7d30bb92f05cf90fb14343d7c993707444ff08df))
* retain only 10% of trust ratio when expanding trust paths ([07b1607](https://github.com/knowledgepixels/nanopub-registry/commit/07b1607d7645b97494e5f2348caff56017c5a371))
* seed AgentFilter in simpleLoad tests to match new filter behavior ([ee020f3](https://github.com/knowledgepixels/nanopub-registry/commit/ee020f3e7b4032e171ed3b9555b99ade62b6f2b5))
* show type hash instead of null when unhash lookup fails ([53d2d64](https://github.com/knowledgepixels/nanopub-registry/commit/53d2d64fb8e834b00507f4ddba162e1f4edf7e7e))
* skip agent link rendering for accounts with blank agent field ([7cae2d7](https://github.com/knowledgepixels/nanopub-registry/commit/7cae2d7d3577bdc0fe62527995f37d6e4a635c12))
* strict API param validation, tolerant env var parsing ([5081e7b](https://github.com/knowledgepixels/nanopub-registry/commit/5081e7b7d1bf8b0e8bb073bbb6011b2b093e3612))

### Documentation

* add TODO about "$" list fetching from type-restricted peers ([a69a213](https://github.com/knowledgepixels/nanopub-registry/commit/a69a21375cecf4279a12c88741973fd52666e029))
* update design doc for seqNum rename and type-based coverage ([88ff001](https://github.com/knowledgepixels/nanopub-registry/commit/88ff001103bb0c9c6431962f5438a69a93d75573))

### Performance improvements

* batch seqNum allocation to reduce global counter contention ([286ff99](https://github.com/knowledgepixels/nanopub-registry/commit/286ff993903207c4e6afac6b3ffdf75ed59eec62))
* per-type fetching in LOAD_FULL and RUN_OPTIONAL_LOAD when restricted ([fdeee85](https://github.com/knowledgepixels/nanopub-registry/commit/fdeee853e2365ed5fe4cd3f592fd16d658c220d2))

### General maintenance

* setting next snapshot version [skip ci] ([82c3b46](https://github.com/knowledgepixels/nanopub-registry/commit/82c3b46f8ebbd413f5d8b4d9dfe8c1327fcd0d95))

### Refactoring

* simplify coverage enforcement — $ means all covered types ([072b9e1](https://github.com/knowledgepixels/nanopub-registry/commit/072b9e1fd2895beff63a4e252568bd38afd5e010))
* use whitespace as separator for REGISTRY_PEER_URLS and REGISTRY_COVERAGE_TYPES ([f61ad77](https://github.com/knowledgepixels/nanopub-registry/commit/f61ad7760c882037889b33f28f622e731fa54a8c))
* use whitespace instead of comma as separator for REGISTRY_COVERAGE_AGENTS ([76dd382](https://github.com/knowledgepixels/nanopub-registry/commit/76dd382ce6995bce17b881e7e5c34f89adc24c56))

## [1.6.0](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.5.1...nanopub-registry-1.6.0) (2026-03-26)

### Features

* checksum-based skip for incremental per-pubkey list sync ([18d78ef](https://github.com/knowledgepixels/nanopub-registry/commit/18d78ef3c3019cad86f6038e962c9ffb6c66aa19))

### Bug Fixes

* eliminate duplicate key race conditions in nanopub and list entry insertion ([c2cfbf5](https://github.com/knowledgepixels/nanopub-registry/commit/c2cfbf51aafdee0348df277baddb62d954b7b06a))

### Performance improvements

* eliminate redundant signature verification in POST and peer sync paths ([d5db5b8](https://github.com/knowledgepixels/nanopub-registry/commit/d5db5b8d817242b9b19073c1534c0a33da868711)), closes [#87](https://github.com/knowledgepixels/nanopub-registry/issues/87)
* optimize DB operations for replication speed ([14c5414](https://github.com/knowledgepixels/nanopub-registry/commit/14c5414e47742e65449ff6ffd8576a2fc9076cef))
* parallel stream loading, batched RUN_OPTIONAL_LOAD, skip idle discoverPubkeys ([566d783](https://github.com/knowledgepixels/nanopub-registry/commit/566d7831d5d51f80bb2aa4d31b1d017e314ee536))

### General maintenance

* Remove preliminary report pdf ([791cfde](https://github.com/knowledgepixels/nanopub-registry/commit/791cfde17b6f9b7ad0bc6689ba591c144ceb46b8))
* setting next snapshot version [skip ci] ([18fdefb](https://github.com/knowledgepixels/nanopub-registry/commit/18fdefb55fbfcb316491ac614f6fda333e1b7c19))
* Update .gitignore ([d12d6ee](https://github.com/knowledgepixels/nanopub-registry/commit/d12d6eeca0ec8e76aa7e915fc79f85c704a4ea03))

## [1.5.1](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.5.0...nanopub-registry-1.5.1) (2026-03-20)

### Bug Fixes

* prevent CHECK_NEW starvation when REGISTRY_PRIORITIZE_ALL_PUBKEYS is enabled ([0cbac1d](https://github.com/knowledgepixels/nanopub-registry/commit/0cbac1d861acc11272136a83d7a07339f696eb88))

### General maintenance

* Adjust run.sh script to do `mvn clean` ([a0be72a](https://github.com/knowledgepixels/nanopub-registry/commit/a0be72ae57de4cfc195ccc702f40f29b47033b83))
* setting next snapshot version [skip ci] ([11b9344](https://github.com/knowledgepixels/nanopub-registry/commit/11b9344c835b548b3f296d3be4ed3bff8b310aeb))

## [1.5.0](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.4.0...nanopub-registry-1.5.0) (2026-03-18)

### Features

* add /get/ path that forwards to Nanodash for HTML requests ([b550996](https://github.com/knowledgepixels/nanopub-registry/commit/b550996232c89e69502a55159aadc38cba65e67c)), closes [#82](https://github.com/knowledgepixels/nanopub-registry/issues/82)
* stop pushing nanopubs to legacy server and skip test-instance peers during sync ([93a1003](https://github.com/knowledgepixels/nanopub-registry/commit/93a1003bd4495886405e51d055024665659acc54))

### Bug Fixes

* prevent peer sync from stalling due to MongoDB transaction timeout ([f537889](https://github.com/knowledgepixels/nanopub-registry/commit/f5378890a1281732d3bfcb58ea40682910f51902))

### Tests

* **deps:** add org.nanopub:nanopub-testsuite-connector dependency v1.0.0 ([d4c5f9d](https://github.com/knowledgepixels/nanopub-registry/commit/d4c5f9d22589e5a8e0f05f6ba66a3a20b691e54d))
* update test cases to use NanopubTestSuite for nanopub retrieval ([1c233da](https://github.com/knowledgepixels/nanopub-registry/commit/1c233da1685ab88d133008d77b4dcd83fc5cc682))

### Build and continuous integration

* **release:** automate main branch update after release ([2f82131](https://github.com/knowledgepixels/nanopub-registry/commit/2f82131ce33d801c1bb2668a7df6102a9b81cb23))

### General maintenance

* remove testsuite submodule ([d6861f9](https://github.com/knowledgepixels/nanopub-registry/commit/d6861f950d095efbbd087a1ba7da8767c571dd6f))
* remove unused git submodule execution from exec-maven-plugin ([8baebcd](https://github.com/knowledgepixels/nanopub-registry/commit/8baebcdc3cd87a7ed38f8ed4e5da8705b7609dee))
* setting next snapshot version [skip ci] ([fa00b56](https://github.com/knowledgepixels/nanopub-registry/commit/fa00b56efc5b5a2edf06539c7a18d299ab452908))

## [1.4.0](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.3.0...nanopub-registry-1.4.0) (2026-03-13)

### Features

* add REGISTRY_LOAD_ALL_PUBKEYS env var for high-priority loading of all pubkeys ([#80](https://github.com/knowledgepixels/nanopub-registry/issues/80)) ([9c6b7a6](https://github.com/knowledgepixels/nanopub-registry/commit/9c6b7a6e56ccee98cc04ebd7f441655823ae3120))

### Dependency updates

* **core-deps:** update dependency org.mongodb:mongo-driver-sync to v4.10.0 ([b37a04d](https://github.com/knowledgepixels/nanopub-registry/commit/b37a04df1f7cbdefc9f66d3a004bd624f49e101d))

### Bug Fixes

* update stale env var name in monitor.sh comment ([4662189](https://github.com/knowledgepixels/nanopub-registry/commit/46621897d425a09ec3f4fc08d4d1500385124061))

### General maintenance

* setting next snapshot version [skip ci] ([9d469cd](https://github.com/knowledgepixels/nanopub-registry/commit/9d469cdaeec4e103d380ac6fb32998093212e53a))

### Refactoring

* remove full fetch mechanism (REGISTRY_PERFORM_FULL_FETCH) ([3b08a37](https://github.com/knowledgepixels/nanopub-registry/commit/3b08a37af88854d11d28c42c6d0e72abfa6fdbe0))

## [1.3.0](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.2.0...nanopub-registry-1.3.0) (2026-03-10)

### Features

* stream all nanopub IDs in JSON format with sort options ([aa99ba3](https://github.com/knowledgepixels/nanopub-registry/commit/aa99ba39c36463dde325508307a765f8950fec5d))

### Dependency updates

* **deps:** update commons-exec to 1.6.0 ([1310e61](https://github.com/knowledgepixels/nanopub-registry/commit/1310e61f5e87006d6c5409e86ccb656c6ba4cf37))
* **deps:** update exec-maven-plugin to 3.6.3 ([690d77b](https://github.com/knowledgepixels/nanopub-registry/commit/690d77bd22cdd960d853eaee47bf19c829f33bed))
* **deps:** update jacoco-maven-plugin to 0.8.14 ([a435c0a](https://github.com/knowledgepixels/nanopub-registry/commit/a435c0a94f0a28e932a941534a21af5a723136e8))
* **deps:** update jelly to 3.7.1 ([4a9193d](https://github.com/knowledgepixels/nanopub-registry/commit/4a9193d4250d40d126a1b55466e67051179c516d))
* **deps:** update maven-shade-plugin to 3.6.2 ([44c5476](https://github.com/knowledgepixels/nanopub-registry/commit/44c5476efe34c1ec3353d3dc7c3af32820630a2a))
* **deps:** update maven-surefire-plugin to 3.5.5 ([bb80ffb](https://github.com/knowledgepixels/nanopub-registry/commit/bb80ffb329a5919a892bbda852eacabd90611c9b))
* **deps:** update mockito-core to 5.22.0 ([e53b33f](https://github.com/knowledgepixels/nanopub-registry/commit/e53b33f28abfadeca54bba1e73b6b8ef417e1420))
* **deps:** update nanopub to 1.86.1 ([ff4bd01](https://github.com/knowledgepixels/nanopub-registry/commit/ff4bd01e665e58bb27211263b575200619018406))
* **deps:** update testcontainers to 2.0.3 ([e52d780](https://github.com/knowledgepixels/nanopub-registry/commit/e52d780a58ab293f4b52a81da275aa8326c92848))

### Bug Fixes

* **test:** use system temp dir to avoid collision with Docker volumes ([98428e8](https://github.com/knowledgepixels/nanopub-registry/commit/98428e88226521140ebcd802fc32c218614f1d72))

### General maintenance

* setting next snapshot version [skip ci] ([545915b](https://github.com/knowledgepixels/nanopub-registry/commit/545915baa4f2e3beb47b161ce8740f7d42f1a4c7))

## [1.2.0](https://github.com/knowledgepixels/nanopub-registry/compare/nanopub-registry-1.1.1...nanopub-registry-1.2.0) (2026-03-10)

### Features

* add /debug/tasks endpoint to expose the task queue ([94dcb1d](https://github.com/knowledgepixels/nanopub-registry/commit/94dcb1de97c00ab3be4276642953dd8f5e473f2d))
* add REGISTRY_PERFORM_FULL_FETCH env var to disable full fetch ([9df347b](https://github.com/knowledgepixels/nanopub-registry/commit/9df347b9794ab89f95c44f29c4741c65ed5d9ef7))
* show currently running task on /debug/tasks page ([e0e4e51](https://github.com/knowledgepixels/nanopub-registry/commit/e0e4e51bd10ef80d47d0fd30d3cd8ed93cbee538))

### Bug Fixes

* close HTTP response in retrieveNanopubsFromPeers to prevent connection pool exhaustion ([76e42f6](https://github.com/knowledgepixels/nanopub-registry/commit/76e42f6aa1619a65bce2fc6d294437180bda1a37))
* improve resilience of peer sync ([01dcace](https://github.com/knowledgepixels/nanopub-registry/commit/01dcace4859ce80a0baf5d01d1589f4e3f972950))

### General maintenance

* setting next snapshot version [skip ci] ([fb23502](https://github.com/knowledgepixels/nanopub-registry/commit/fb2350240900efef00783151c034b20fb5d7ce30))

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
