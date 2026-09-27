import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import SplashScreen from './src/screens/SplashScreen';
import LoginScreen from './src/screens/LoginScreen';
import SignupScreen from './src/screens/SignupScreen';
import DashboardScreen from './src/screens/DashboardScreen';
import LiveProtectionScreen from './src/screens/LiveProtectionScreen';
import HistoryScreen from './src/screens/HistoryScreen';
import SettingsScreen from './src/screens/SettingsScreen';
import InterpreterScreen from './src/screens/InterpreterScreen';
import TrainNavigator from './src/screens/train/TrainNavigator';
import { ProtectionProvider } from './src/context/ProtectionContext';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { Home, Shield, Mic, Clock, Settings as SettingsIcon } from 'lucide-react-native';

const Stack = createNativeStackNavigator();
const Tab = createBottomTabNavigator();

// --- Navigators ---

function MainTabs() {
  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarStyle: {
          backgroundColor: 'rgba(255, 255, 255, 0.95)',
          borderTopColor: '#E5E7EB',
          position: 'absolute',
          paddingBottom: 5,
          height: 60,
          elevation: 0,
          borderTopWidth: 1,
        },
        tabBarActiveTintColor: '#06B6D4',
        tabBarInactiveTintColor: '#9CA3AF',
      }}
    >
      <Tab.Screen 
        name="Dashboard" 
        component={DashboardScreen} 
        options={{
          tabBarIcon: ({ color, size }) => <Home color={color} size={size} />
        }}
      />
      <Tab.Screen 
        name="Protection" 
        component={LiveProtectionScreen} 
        options={{
          tabBarIcon: ({ color, size }) => <Shield color={color} size={size} />
        }}
      />
      <Tab.Screen 
        name="Train" 
        component={TrainNavigator} 
        options={{
          tabBarIcon: ({ color, size }) => <Mic color={color} size={size} />
        }}
      />
      <Tab.Screen 
        name="History" 
        component={HistoryScreen} 
        options={{
          tabBarIcon: ({ color, size }) => <Clock color={color} size={size} />
        }}
      />
      <Tab.Screen
        name="Interpreter"
        component={InterpreterScreen}
        options={{
          tabBarIcon: ({ color, size }) => <Mic color={color} size={size} />
        }}
      />
      <Tab.Screen 
        name="Settings" 
        component={SettingsScreen} 
        options={{
          tabBarIcon: ({ color, size }) => <SettingsIcon color={color} size={size} />
        }}
      />
    </Tab.Navigator>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <ProtectionProvider>
        <NavigationContainer>
          <Stack.Navigator screenOptions={{ headerShown: false, animation: 'fade' }}>
            <Stack.Screen name="Splash" component={SplashScreen} />
            <Stack.Screen name="Login" component={LoginScreen} />
            <Stack.Screen name="Signup" component={SignupScreen} />
            <Stack.Screen name="MainTabs" component={MainTabs} />
          </Stack.Navigator>
        </NavigationContainer>
      </ProtectionProvider>
    </SafeAreaProvider>
  );
}
