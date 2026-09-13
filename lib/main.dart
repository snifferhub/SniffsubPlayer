import 'package:flutter/cupertino.dart';

import 'screens/browser_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SniffSubPlayerApp());
}

class SniffSubPlayerApp extends StatelessWidget {
  const SniffSubPlayerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const CupertinoApp(
      debugShowCheckedModeBanner: false,
      title: 'SniffSubPlayer',
      theme: CupertinoThemeData(
        brightness: Brightness.light,
        primaryColor: CupertinoColors.systemBlue,
        scaffoldBackgroundColor: CupertinoColors.systemGroupedBackground,
        barBackgroundColor: CupertinoDynamicColor.withBrightness(
          color: Color(0xF7F7F7F7),
          darkColor: Color(0xF71C1C1E),
        ),
      ),
      home: BrowserScreen(),
    );
  }
}
