Option Explicit

' The older Windows package recursively removes its installation directory during upgrade.
' Preserve the opaque DPAPI file outside that directory before Windows Installer removes it.
Function PreserveProfessorOsSession()
    Dim fs, shell, localData, legacy, profile, destination, source, namePattern, target, temporary
    Set fs = CreateObject("Scripting.FileSystemObject")
    Set shell = CreateObject("WScript.Shell")
    localData = shell.ExpandEnvironmentStrings("%LOCALAPPDATA%")
    PreserveProfessorOsSession = 3
    If Not fs.FolderExists(localData) Then Exit Function

    profile = fs.BuildPath(localData, "Professor OS")
    legacy = fs.BuildPath(profile, "sessions")
    PreserveProfessorOsSession = 1
    If Not fs.FolderExists(legacy) Then Exit Function
    PreserveProfessorOsSession = 3
    If (fs.GetFolder(profile).Attributes And 1024) <> 0 Then Exit Function
    If (fs.GetFolder(legacy).Attributes And 1024) <> 0 Then Exit Function

    profile = fs.BuildPath(localData, "ProfessorOS")
    If Not fs.FolderExists(profile) Then fs.CreateFolder profile
    If (fs.GetFolder(profile).Attributes And 1024) <> 0 Then Exit Function
    destination = fs.BuildPath(profile, "sessions")
    If Not fs.FolderExists(destination) Then fs.CreateFolder destination
    If (fs.GetFolder(destination).Attributes And 1024) <> 0 Then Exit Function

    Set namePattern = New RegExp
    namePattern.Pattern = "^[0-9a-f]{64}\.dpapi$"
    For Each source In fs.GetFolder(legacy).Files
        If namePattern.Test(source.Name) Then
            If (source.Attributes And 1024) <> 0 Then Exit Function
            If source.Size < 1 Or source.Size > 8192 Then Exit Function
            target = fs.BuildPath(destination, source.Name)
            If Not fs.FileExists(target) Then
                temporary = fs.BuildPath(destination, fs.GetTempName())
                fs.CopyFile source.Path, temporary, False
                fs.MoveFile temporary, target
            End If
        End If
    Next
    PreserveProfessorOsSession = 1
End Function
