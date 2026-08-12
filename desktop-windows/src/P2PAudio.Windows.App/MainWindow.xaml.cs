using System.Runtime.InteropServices;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using P2PAudio.Windows.App.Logging;
using P2PAudio.Windows.App.ViewModels;
using P2PAudio.Windows.Core.Models;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics;
using WinRT.Interop;

namespace P2PAudio.Windows.App;

public sealed partial class MainWindow : Window
{
    private const int SwShow = 5;
    private const int SwRestore = 9;

    private DispatcherTimer? _transientTimer;
    private bool _startupInitialized;

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(nint hWnd, int nCmdShow);

    public MainWindow()
    {
        ViewModel = new MainViewModel(initializeImmediately: false);
        InitializeComponent();
        TryApplySystemBackdrop();
        Activated += OnActivated;
        Closed += OnClosed;
        ViewModel.PropertyChanged += OnViewModelPropertyChanged;
        UpdateFlowCards();
    }

    public MainViewModel ViewModel { get; }

    public void Present()
    {
        Activate();
        EnsureWindowVisible();
        _ = DispatcherQueue.TryEnqueue(EnsureWindowVisible);
    }

    private async void OnActivated(object sender, WindowActivatedEventArgs args)
    {
        _ = args;
        try
        {
            EnsureWindowVisible();
            if (_startupInitialized)
            {
                return;
            }

            _startupInitialized = true;
            Activated -= OnActivated;

            await Task.Yield();
            await ViewModel.InitializeAsync();
        }
        catch (Exception ex)
        {
            AppLogger.E(
                "MainWindow",
                "startup_initialize_failed",
                "Main view model initialization failed",
                exception: ex);
            ViewModel.StatusMessage = "起動時の初期化に失敗しました。";
            ViewModel.FailureHintLabel = $"起動時の初期化に失敗しました: {ex.Message}";
            ViewModel.CurrentStreamState = StreamState.Failed;
        }
    }

    private void OnViewModelPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (!DispatcherQueue.HasThreadAccess)
        {
            _ = DispatcherQueue.TryEnqueue(() => OnViewModelPropertyChanged(sender, e));
            return;
        }

        if (e.PropertyName is nameof(MainViewModel.CurrentSetupStep) or nameof(MainViewModel.CurrentStreamState) or nameof(MainViewModel.IsVerificationPending) or nameof(MainViewModel.FailureHintLabel) or nameof(MainViewModel.SelectedTransportMode))
        {
            UpdateFlowCards();
        }
        if (e.PropertyName == nameof(MainViewModel.ActiveSessionId))
        {
            SessionIdPanel.Visibility = string.IsNullOrEmpty(ViewModel.ActiveSessionId)
                ? Visibility.Collapsed : Visibility.Visible;
        }
    }

    private void UpdateFlowCards()
    {
        var step = ViewModel.CurrentSetupStep;
        var streamState = ViewModel.CurrentStreamState;
        var showEntryCard = step == SetupStep.Entry || streamState == StreamState.Failed;
        var hideSetupCards = streamState is StreamState.Streaming or StreamState.Interrupted or StreamState.Ended or StreamState.Failed;

        EntryCard.Visibility = showEntryCard ? Visibility.Visible : Visibility.Collapsed;
        PathDiagnosingCard.Visibility = !hideSetupCards &&
            (step == SetupStep.PathDiagnosing || step == SetupStep.UdpSenderDiscovering)
                ? Visibility.Visible
                : Visibility.Collapsed;
        SenderShowInitCard.Visibility = !hideSetupCards && step == SetupStep.SenderShowInit ? Visibility.Visible : Visibility.Collapsed;
        SenderManualFallbackExpander.Visibility = ViewModel.ShowManualPayloadFallback
            ? Visibility.Visible
            : Visibility.Collapsed;
        SenderVerifyCodeCard.Visibility = !hideSetupCards && step == SetupStep.SenderVerifyCode && ViewModel.IsVerificationPending
            ? Visibility.Visible
            : Visibility.Collapsed;
        ListenerScanInitCard.Visibility = !hideSetupCards && step == SetupStep.ListenerScanInit ? Visibility.Visible : Visibility.Collapsed;
        ListenerShowConfirmCard.Visibility = !hideSetupCards && step == SetupStep.ListenerShowConfirm ? Visibility.Visible : Visibility.Collapsed;
        UdpOpusFrameDurationPanel.Visibility = ViewModel.SelectedTransportMode == TransportMode.UdpOpus
            ? Visibility.Visible
            : Visibility.Collapsed;

        var showFailure = streamState is StreamState.Failed or StreamState.Interrupted
            || !string.IsNullOrEmpty(ViewModel.FailureHintLabel);
        StatusFailurePanel.Visibility = showFailure ? Visibility.Visible : Visibility.Collapsed;

        UpdateStatusHeroVisual(streamState);
    }

    private void UpdateStatusHeroVisual(StreamState state)
    {
        string borderKey = state switch
        {
            StreamState.Streaming => "SystemFillColorSuccessBrush",
            StreamState.Capturing or StreamState.Connecting => "SystemFillColorAttentionBrush",
            StreamState.Interrupted => "SystemFillColorCautionBrush",
            StreamState.Failed => "SystemFillColorCriticalBrush",
            _ => "CardStrokeColorDefaultBrush"
        };

        if (Application.Current.Resources.TryGetValue(borderKey, out var brush) && brush is Brush b)
        {
            StatusHeroCard.BorderBrush = b;
        }
        else if (Application.Current.Resources.TryGetValue("CardStrokeColorDefaultBrush", out var fallback) && fallback is Brush fb)
        {
            StatusHeroCard.BorderBrush = fb;
        }

        StatusHeroCard.BorderThickness = state is StreamState.Streaming or StreamState.Interrupted
            or StreamState.Failed or StreamState.Capturing or StreamState.Connecting
            ? new Thickness(4, 1, 1, 1)
            : new Thickness(1);
    }

    private async void OnStartSenderClick(object sender, RoutedEventArgs e)
    {
        await ViewModel.StartSenderAsync();
    }

    private void OnStartListenerClick(object sender, RoutedEventArgs e)
    {
        ViewModel.StartListener();
    }

    private void OnTransportModeSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _ = sender;
        _ = e;
        ViewModel.SelectTransportMode(TransportModeComboBox.SelectedIndex == 1
            ? TransportMode.UdpOpus
            : TransportMode.WebRtc);
    }

    private void OnUdpOpusFrameDurationSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _ = sender;
        _ = e;
        var frameDurationMs = UdpOpusFrameDurationComboBox.SelectedIndex switch
        {
            0 => 5,
            1 => 10,
            2 => 20,
            3 => 40,
            4 => 60,
            _ => 20
        };
        ViewModel.SelectUdpOpusFrameDuration(frameDurationMs);
    }

    private void OnUdpOpusApplicationSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _ = sender;
        _ = e;
        var application = UdpOpusApplicationComboBox.SelectedIndex == 1
            ? Services.UdpOpusApplication.Audio
            : Services.UdpOpusApplication.RestrictedLowDelay;
        ViewModel.SelectUdpOpusApplication(application);
    }

    private async void OnPastePayloadClick(object sender, RoutedEventArgs e)
    {
        await ViewModel.PasteFromClipboardAsync();
    }

    private async void OnProcessSenderConfirmPayloadClick(object sender, RoutedEventArgs e)
    {
        var text = SenderConfirmPayloadInput.Text;
        if (!string.IsNullOrWhiteSpace(text))
        {
            await ViewModel.ProcessInputPayloadAsync(text);
        }
    }

    private async void OnProcessListenerInitPayloadClick(object sender, RoutedEventArgs e)
    {
        var text = ListenerInitPayloadInput.Text;
        if (!string.IsNullOrWhiteSpace(text))
        {
            await ViewModel.ProcessInputPayloadAsync(text);
        }
    }

    private async void OnApproveVerificationClick(object sender, RoutedEventArgs e)
    {
        await ViewModel.ApproveVerificationAndConnectAsync();
    }

    private void OnRejectVerificationClick(object sender, RoutedEventArgs e)
    {
        ViewModel.RejectVerificationAndRestart();
    }

    private void OnStopClick(object sender, RoutedEventArgs e)
    {
        ViewModel.Stop();
    }

    private void OnCopyPayloadClick(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(ViewModel.CurrentPayload))
            return;

        TryCopyTextToClipboard(
            ViewModel.CurrentPayload,
            successMessage: "接続データをクリップボードへコピーしました。",
            failureMessage: "接続データをクリップボードへコピーできませんでした。"
        );
    }

    private void OnCopyConnectionCodeClick(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(ViewModel.CurrentConnectionCode))
            return;

        TryCopyTextToClipboard(
            ViewModel.CurrentConnectionCode,
            successMessage: "接続コードをクリップボードへコピーしました。",
            failureMessage: "接続コードをクリップボードへコピーできませんでした。"
        );
    }

    private void TryCopyTextToClipboard(string text, string successMessage, string failureMessage)
    {
        try
        {
            var dataPackage = new DataPackage();
            dataPackage.SetText(text);
            Clipboard.SetContent(dataPackage);
            ShowTransientMessage(successMessage);
        }
        catch (Exception ex)
        {
            AppLogger.E("MainWindow", "clipboard_copy_failed", failureMessage, exception: ex);
            ShowTransientMessage($"{failureMessage} {ex.Message}");
        }
    }

    private void ShowTransientMessage(string message)
    {
        TransientInfoBar.Message = message;
        TransientInfoBar.IsOpen = true;

        _transientTimer?.Stop();
        _transientTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _transientTimer.Tick += (_, _) =>
        {
            TransientInfoBar.IsOpen = false;
            _transientTimer.Stop();
        };
        _transientTimer.Start();
    }

    private void OnClosed(object sender, WindowEventArgs args)
    {
        _ = sender;
        _ = args;

        Activated -= OnActivated;
        Closed -= OnClosed;
        ViewModel.PropertyChanged -= OnViewModelPropertyChanged;

        _transientTimer?.Stop();
        _transientTimer = null;

        try
        {
            AppLogger.I(
                "MainWindow",
                "shutdown_started",
                "Running main view model shutdown before application exit");
            ViewModel.Shutdown();
            AppLogger.I(
                "MainWindow",
                "shutdown_completed",
                "Main view model shutdown completed before application exit");
        }
        catch (Exception ex)
        {
            AppLogger.E(
                "MainWindow",
                "shutdown_failed",
                "Main view model shutdown failed during window close",
                exception: ex);
        }
        finally
        {
            if (Application.Current is App app)
            {
                app.OnMainWindowClosed(this);
            }
        }
    }

    private void TryApplySystemBackdrop()
    {
        if (!MicaController.IsSupported())
        {
            return;
        }

        SystemBackdrop = new Microsoft.UI.Xaml.Media.MicaBackdrop();
    }

    private void EnsureWindowVisible()
    {
        var appWindow = AppWindow;
        if (appWindow.Size.Width <= 0 || appWindow.Size.Height <= 0)
        {
            appWindow.Resize(new SizeInt32(980, 760));
        }
        if (!appWindow.IsVisible)
        {
            appWindow.Show();
        }

        var windowHandle = WindowNative.GetWindowHandle(this);
        if (windowHandle == nint.Zero)
        {
            return;
        }

        _ = ShowWindow(windowHandle, SwRestore);
        _ = ShowWindow(windowHandle, SwShow);
    }
}
