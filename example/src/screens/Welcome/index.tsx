import React from 'react'
import { Text, StyleSheet } from 'react-native'
import { ExButton, ExWrapper } from '../../components/commons'

const WelcomeScreen = ({ navigation }: any) => {
  return (
    <ExWrapper>
      <Text style={styles.title}>React Native Background Downloader</Text>

      <ExButton
        title={'1- Download Example'}
        onPress={() => navigation.navigate('root.basic_example')}
      />

      <ExButton
        title={'2- Upload Example'}
        onPress={() => navigation.navigate('root.upload_example')}
      />
    </ExWrapper>
  )
}

const styles = StyleSheet.create({
  title: {
    fontSize: 24,
    fontWeight: '500',
    textAlign: 'center',
    alignSelf: 'center',
    marginTop: 12,
  },
  description: {
    fontSize: 18,
    fontWeight: '500',
    textAlign: 'center',
    alignSelf: 'center',
    marginTop: 6,
  },
})

export default WelcomeScreen
