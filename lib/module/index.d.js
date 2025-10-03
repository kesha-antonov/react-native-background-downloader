"use strict";

// Type definitions for @kesha-antonov/react-native-background-downloader 2.6
// Project: https://github.com/kesha-antonov/react-native-background-downloader
// Definitions by: Philip Su <https://github.com/fivecar>,
//                 Adam Hunter <https://github.com/adamrhunter>,
//                 Junseong Park <https://github.com/Kweiza>
// Definitions: https://github.com/DefinitelyTyped/DefinitelyTyped

export let SavedTaskState = /*#__PURE__*/function (SavedTaskState) {
  SavedTaskState[SavedTaskState["TaskRunning"] = 0] = "TaskRunning";
  SavedTaskState[SavedTaskState["TaskSuspended"] = 1] = "TaskSuspended";
  SavedTaskState[SavedTaskState["TaskCanceling"] = 2] = "TaskCanceling";
  SavedTaskState[SavedTaskState["TaskCompleted"] = 3] = "TaskCompleted";
  return SavedTaskState;
}({});
//# sourceMappingURL=index.d.js.map