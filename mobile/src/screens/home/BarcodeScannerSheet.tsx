/**
 * Full-screen barcode scanner. Same flow as `BarcodeScannerView` — camera, then Open Food Facts.
 * Errors use a system alert, matching `BarcodeLookupAlertPresenter`.
 */

import { CameraView, useCameraPermissions } from 'expo-camera';
import { useEffect, useRef, useState } from 'react';
import { Linking, Modal, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon } from '../../components/Icon';
import { AppText, PrimaryButton } from '../../components/primitives';
import { sanitizeBarcode } from '../../domain/food/openFoodFacts';
import { useTheme } from '../../theme';

interface BarcodeScannerSheetProps {
  visible: boolean;
  onDismiss: () => void;
  onScan: (barcode: string) => void;
}

export function BarcodeScannerSheet({ visible, onDismiss, onScan }: BarcodeScannerSheetProps) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [permission, requestPermission] = useCameraPermissions();
  const scanned = useRef(false);
  const [torch, setTorch] = useState(false);

  useEffect(() => {
    if (visible) {
      scanned.current = false;
      setTorch(false);
      if (permission && !permission.granted && permission.canAskAgain) {
        void requestPermission();
      }
    }
  }, [visible, permission, requestPermission]);

  const denied = permission !== null && !permission.granted && !permission.canAskAgain;

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onDismiss} statusBarTranslucent>
      <View style={{ flex: 1, backgroundColor: '#000' }}>
        {permission?.granted ? (
          <CameraView
            style={StyleSheet.absoluteFill}
            facing="back"
            enableTorch={torch}
            barcodeScannerSettings={{ barcodeTypes: ['ean13', 'ean8', 'upc_a', 'upc_e', 'code128', 'code39'] }}
            onBarcodeScanned={({ data }) => {
              if (scanned.current) return;
              const code = sanitizeBarcode(data);
              if (!code) return;
              scanned.current = true;
              onScan(code);
            }}
          />
        ) : (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: theme.spacing.xl, gap: 16 }}>
            <Icon name="barcode" size={48} color="#fff" />
            <AppText variant="headline" style={{ color: '#fff' }} align="center">
              {denied ? 'Camera access is needed to scan barcodes.' : 'Allow camera access to scan a barcode.'}
            </AppText>
            {denied ? (
              <PrimaryButton title="Open Settings" onPress={() => void Linking.openSettings()} />
            ) : (
              <PrimaryButton title="Allow Camera" onPress={() => void requestPermission()} />
            )}
          </View>
        )}
        <View
          pointerEvents="none"
          style={{
            ...StyleSheet.absoluteFill,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <View style={{ width: 240, height: 140, borderWidth: 2, borderColor: theme.colors.accent, borderRadius: 16 }} />
        </View>
        <View style={{ position: 'absolute', top: insets.top + 12, left: 16, right: 16, flexDirection: 'row', justifyContent: 'space-between' }}>
          <Pressable accessibilityRole="button" onPress={onDismiss} style={{ padding: 8 }}>
            <Icon name="xmark" size={28} color="#fff" />
          </Pressable>
          <Pressable accessibilityRole="button" onPress={() => setTorch((on) => !on)} style={{ padding: 8 }}>
            <Icon name="bolt.fill" size={24} color={torch ? theme.colors.accent : '#fff'} />
          </Pressable>
        </View>
        <AppText variant="footnote" align="center" style={{ position: 'absolute', bottom: insets.bottom + 24, left: 24, right: 24, color: '#fff' }}>
          Line up the barcode. Nutrition comes from Open Food Facts.
        </AppText>
      </View>
    </Modal>
  );
}
