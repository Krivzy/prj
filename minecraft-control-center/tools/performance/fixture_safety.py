"""Locks and private permissions for newly created benchmark output only.

No server discovery, termination, configuration changes or production imports.
Windows is the reviewed execution platform; POSIX locking is used only in tests.
"""
import os
import stat
import subprocess


def ordinary(path, directory=False):
    value=path.lstat()
    if stat.S_ISLNK(value.st_mode) or getattr(value,'st_file_attributes',0)&0x400:
        raise RuntimeError('Linked/reparse paths are rejected')
    if not (stat.S_ISDIR(value.st_mode) if directory else stat.S_ISREG(value.st_mode)):
        raise RuntimeError('Unexpected fixture path kind')
    return value


def restrict(path, directory=False):
    """Called only for the harness's own .private directory and new children."""
    ordinary(path,directory)
    if os.name!='nt':
        if path.stat().st_uid!=os.geteuid():raise RuntimeError('Output must belong to the current user')
        path.chmod(0o700 if directory else 0o600);return
    literal=str(path).replace("'","''")
    kind='DirectorySecurity' if directory else 'FileSecurity'
    inheritance="'ContainerInherit,ObjectInherit','None'," if directory else ''
    script=("$ErrorActionPreference='Stop';"
            "$env:PSModulePath=[IO.Path]::Combine($PSHOME,'Modules');"
            "Import-Module Microsoft.PowerShell.Security -ErrorAction Stop;"
            "$p='"+literal+"';"
            "$a=New-Object System.Security.AccessControl."+kind+";"
            "$a.SetAccessRuleProtection($true,$false);"
            "$u=[System.Security.Principal.WindowsIdentity]::GetCurrent().User;"
            "$s=New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18');"
            "foreach($id in @($u,$s)){$a.AddAccessRule((New-Object "
            "System.Security.AccessControl.FileSystemAccessRule($id,'FullControl',"+
            inheritance+"'Allow')))};Set-Acl -LiteralPath $p -AclObject $a")
    subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',script],
                   check=True,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)


class OfflineLease:
    """Retained advisory lock for this harness's own output/cache reader markers."""
    def __init__(self,path):
        if path.exists() or path.is_symlink():ordinary(path)
        self.fd=os.open(path,os.O_CREAT|os.O_RDWR|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_BINARY',0),0o600)
        try:
            if not stat.S_ISREG(os.fstat(self.fd).st_mode):raise RuntimeError('Lock must be regular')
            if os.name=='nt':
                import msvcrt
                if os.fstat(self.fd).st_size==0:os.write(self.fd,b'0')
                os.lseek(self.fd,0,os.SEEK_SET);msvcrt.locking(self.fd,msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.flock(self.fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BaseException:
            os.close(self.fd);self.fd=None;raise

    def close(self):
        if self.fd is None:return
        try:
            if os.name=='nt':
                import msvcrt
                os.lseek(self.fd,0,os.SEEK_SET);msvcrt.locking(self.fd,msvcrt.LK_UNLCK,1)
            else:
                import fcntl
                fcntl.flock(self.fd,fcntl.LOCK_UN)
        finally:
            os.close(self.fd);self.fd=None
