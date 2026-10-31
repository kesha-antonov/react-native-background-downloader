// React Native 0.88's strict TypeScript API has no types for deep imports. The spec
// keeps the deep import because older codegen versions only understand that form.
declare module 'react-native/Libraries/Types/CodegenTypes' {
  import type { CodegenTypes } from 'react-native'
  export type EventEmitter<T> = CodegenTypes.EventEmitter<T>
}
