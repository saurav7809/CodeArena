import ctypes as ct
from ctypes import wintypes as win


class BasicLimits(ct.Structure):
    _fields_ = [
        ("process_time", ct.c_longlong), ("job_time", ct.c_longlong), ("flags", win.DWORD),
        ("min_working_set", ct.c_size_t), ("max_working_set", ct.c_size_t), ("active_processes", win.DWORD),
        ("affinity", ct.c_size_t), ("priority", win.DWORD), ("scheduling", win.DWORD),
    ]


class ExtendedLimits(ct.Structure):
    _fields_ = [
        ("basic", BasicLimits), ("io_counters", ct.c_ulonglong * 6),
        ("process_memory", ct.c_size_t), ("job_memory", ct.c_size_t),
        ("peak_process_memory", ct.c_size_t), ("peak_job_memory", ct.c_size_t),
    ]


class WindowsJob:
    """Contain the process tree and cap its committed memory on Windows."""

    EXTENDED_LIMITS = 9
    LIMIT_FLAGS = 0x00002000 | 0x00000200 | 0x00000008  # kill on close, job memory, active process count

    def __init__(self, memory_mb: int):
        self.kernel = ct.WinDLL("kernel32", use_last_error=True)
        self.kernel.CreateJobObjectW.argtypes = [ct.c_void_p, win.LPCWSTR]
        self.kernel.CreateJobObjectW.restype = win.HANDLE
        self.kernel.SetInformationJobObject.argtypes = [win.HANDLE, ct.c_int, ct.c_void_p, win.DWORD]
        self.kernel.AssignProcessToJobObject.argtypes = [win.HANDLE, win.HANDLE]
        self.kernel.OpenProcess.argtypes = [win.DWORD, win.BOOL, win.DWORD]
        self.kernel.OpenProcess.restype = win.HANDLE
        self.kernel.QueryInformationJobObject.argtypes = [win.HANDLE, ct.c_int, ct.c_void_p, win.DWORD, ct.c_void_p]
        self.kernel.CloseHandle.argtypes = [win.HANDLE]
        self.kernel.TerminateJobObject.argtypes = [win.HANDLE, win.UINT]

        self.handle = self.kernel.CreateJobObjectW(None, None)
        if not self.handle:
            raise ct.WinError(ct.get_last_error())
        limits = ExtendedLimits()
        limits.basic.flags = self.LIMIT_FLAGS
        limits.basic.active_processes = 12
        limits.job_memory = memory_mb * 1024 * 1024
        if not self.kernel.SetInformationJobObject(self.handle, self.EXTENDED_LIMITS, ct.byref(limits), ct.sizeof(limits)):
            error = ct.get_last_error()
            self.close()
            raise ct.WinError(error)

    def attach(self, pid: int) -> None:
        process = self.kernel.OpenProcess(0x0001 | 0x0100, False, pid)  # terminate and set quota
        if not process:
            raise ct.WinError(ct.get_last_error())
        try:
            if not self.kernel.AssignProcessToJobObject(self.handle, process):
                raise ct.WinError(ct.get_last_error())
        finally:
            self.kernel.CloseHandle(process)

    def stop(self) -> int | None:
        self.kernel.TerminateJobObject(self.handle, 1)
        limits = ExtendedLimits()
        if self.kernel.QueryInformationJobObject(self.handle, self.EXTENDED_LIMITS, ct.byref(limits), ct.sizeof(limits), None):
            return (limits.peak_job_memory + 1023) // 1024
        return None

    def close(self) -> None:
        self.kernel.CloseHandle(self.handle)
