; Stellar's Windows installer (the .exe), made with Inno Setup from the app
; folder Compose builds (gradle :desktopApp:createDistributable). Inno Setup
; rather than jpackage's own .exe for its Finish page: "Launch Stellar",
; ticked by default. CI builds it — see .github/workflows/desktop.yml:
;
;   iscc /DAppVersion=1.0.0 desktopApp\windows\stellar.iss
;
; Paths are relative to this file.

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
; Fixed, so a newer installer upgrades the installed app.
AppId={{6F1D2B44-8F4E-4C55-9A59-3C1F5E0B7A22}
AppName=Stellar
AppVersion={#AppVersion}
AppVerName=Stellar {#AppVersion}
AppPublisher=Recho Raccoon
AppCopyright=© Recho Raccoon. GPLv3.
; Installed for this Windows user only: no administrator needed.
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\Stellar
DefaultGroupName=Stellar
DisableProgramGroupPage=yes
LicenseFile=..\..\LICENSE
SetupIconFile=..\icons\stellar.ico
UninstallDisplayIcon={app}\Stellar.exe
UninstallDisplayName=Stellar
OutputDir=..\build\inno
OutputBaseFilename=Stellar-Windows
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Stellar is closed (and reopened afterwards) if it's running.
CloseApplications=yes

; The installer speaks the Windows user's language when Stellar does.
; (Korean and Chinese aren't among Inno Setup's own translations.)
[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"
Name: "fr"; MessagesFile: "compiler:Languages\French.isl"
Name: "de"; MessagesFile: "compiler:Languages\German.isl"
Name: "it"; MessagesFile: "compiler:Languages\Italian.isl"
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "ja"; MessagesFile: "compiler:Languages\Japanese.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\build\compose\binaries\main\app\Stellar\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Stellar"; Filename: "{app}\Stellar.exe"
Name: "{autodesktop}\Stellar"; Filename: "{app}\Stellar.exe"; Tasks: desktopicon

[Run]
; The Finish page's "Launch Stellar" box, ticked by default.
Filename: "{app}\Stellar.exe"; Description: "{cm:LaunchProgram,Stellar}"; Flags: nowait postinstall skipifsilent
