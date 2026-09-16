import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { RootNavigator } from './src/navigation/RootNavigator';
import { OnboardingFlow } from './src/screens/onboarding/OnboardingFlow';
import { HostedPaywallSheet } from './src/screens/paywall/HostedPaywallSheet';
import { applyAdaptiveGoalsIfDue } from './src/services/adaptiveGoals';
import { subscribeCompanionSnapshot } from './src/services/companionSnapshot';
import { installPurchasesAdapter } from './src/services/purchases';
import { hydrateAndPersistStores, usePreferences } from './src/state/appStores';
import { appThemeColor, ThemeProvider, useTheme } from './src/theme';

/**
 * Root: hydrate stores, then gate on onboarding exactly like `calorietrackerApp.swift`
 * (`hasCompletedOnboarding`): the full 14-step `OnboardingFlow` first, then the tab bar.
 */
export default function App() {
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    let disposed = false;
    let dispose: (() => void) | undefined;
    const hydration = hydrateAndPersistStores()
      .then((cleanup) => {
        if (disposed) cleanup();
        else dispose = cleanup;
      })
      .catch((error: unknown) => {
        // Storage failures are already reported per store; whatever happened, the app must
        // start with defaults rather than sit on the spinner.
        console.error('[fudai] hydration failed; starting with defaults', error);
      });
    // Purchases only needs the RevenueCat key / native module — run even if hydration rejected.
    // Do not chain off hydration.then(success); that skips install on rejection.
    const purchases = installPurchasesAdapter().catch((error: unknown) =>
      console.warn('[fudai] purchases adapter unavailable', error),
    );
    void Promise.all([hydration, purchases]).finally(() => {
      if (!disposed) {
        setHydrated(true);
        applyAdaptiveGoalsIfDue();
      }
    });
    const unsubscribeSnapshot = subscribeCompanionSnapshot();
    return () => {
      disposed = true;
      dispose?.();
      unsubscribeSnapshot();
    };
  }, []);

  const appearanceMode = usePreferences((p) => p.appearanceMode);
  const accentId = usePreferences((p) => appThemeColor(p.appThemeColor));

  return (
    <SafeAreaProvider>
      <ThemeProvider accentId={accentId} appearanceMode={appearanceMode}>
        <Root hydrated={hydrated} />
      </ThemeProvider>
    </SafeAreaProvider>
  );
}

function Root({ hydrated }: { hydrated: boolean }) {
  const theme = useTheme();
  const hasCompletedOnboarding = usePreferences((p) => p.hasCompletedOnboarding);
  const [paywallVisible, setPaywallVisible] = useState(false);

  return (
    <>
      <StatusBar style={theme.scheme === 'dark' ? 'light' : 'dark'} />
      {!hydrated ? (
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: theme.colors.appBackground }}>
          <ActivityIndicator color={theme.colors.accent} />
        </View>
      ) : hasCompletedOnboarding ? (
        <RootNavigator />
      ) : (
        <>
          <OnboardingFlow onShowPaywall={() => setPaywallVisible(true)} onComplete={() => setPaywallVisible(false)} />
          <HostedPaywallSheet visible={paywallVisible} onDismiss={() => setPaywallVisible(false)} />
        </>
      )}
    </>
  );
}
