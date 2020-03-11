module.exports = function (runtime, scope) {
    importClass(org.autojs.autojs.pluginclient.DevPluginService);
    return DevPluginService.getInstance();
}