using Microsoft.UI.Xaml;
using P2PAudio.Windows.App.Logging;

namespace P2PAudio.Windows.App;

public partial class App : Application
{
    private const uint MessageBoxIconError = 0x00000010;
    private const uint MessageBoxOk = 0x00000000;
    private MainWindow? _window;

    public App()
    {
        InitializeComponent();
        AppLogger.I(
            "App",
            "app_constructed",
            "Windows app constructed",
            new Dictionary<string, object?>
            {
                ["logFile"] = AppLogger.CurrentLogFilePath
            }
        );
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _ = args;

        try
        {
            if (_window is null)
            {
                _window = new MainWindow();
                AppLogger.I("App", "window_created", "Main window created");
            }

            _window.Present();
            RunOnStaBackgroundThread(StartMenuShortcut.EnsureStartMenuShortcut);
            AppLogger.I("App", "app_presented", "Main window presented");
        }
        catch (Exception ex)
        {
            AppLogger.E(
                "App",
                "launch_failed",
                "Windows app failed during launch",
                exception: ex);
            ShowLaunchFailureMessage(ex.Message);
        }
    }

    internal void OnMainWindowClosed(MainWindow closingWindow)
    {
        if (!ReferenceEquals(_window, closingWindow))
        {
            return;
        }

        _window = null;
        AppLogger.I("App", "window_closed", "Main window closed; exiting application");
        Exit();
    }

    private static void RunOnStaBackgroundThread(Action action)
    {
        try
        {
            var thread = new Thread(() =>
            {
                try
                {
                    action();
                }
                catch (Exception ex)
                {
                    AppLogger.E(
                        "App",
                        "background_action_failed",
                        "Background startup action failed",
                        exception: ex);
                }
            })
            {
                IsBackground = true,
                Name = "StartMenuShortcutRefresh"
            };

            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
        }
        catch (Exception ex)
        {
            AppLogger.E(
                "App",
                "background_action_start_failed",
                "Failed to start background startup action",
                exception: ex);
        }
    }

    [System.Runtime.InteropServices.DllImport(
        "user32.dll",
        CharSet = System.Runtime.InteropServices.CharSet.Unicode,
        SetLastError = true)]
    private static extern int MessageBox(nint hWnd, string text, string caption, uint type);

    private static void ShowLaunchFailureMessage(string failureMessage)
    {
        _ = MessageBox(
            hWnd: 0,
            text: $"{failureMessage}{Environment.NewLine}{Environment.NewLine}ログ: {AppLogger.CurrentLogFilePath}",
            caption: "P2PAudio",
            type: MessageBoxOk | MessageBoxIconError);
    }
}
